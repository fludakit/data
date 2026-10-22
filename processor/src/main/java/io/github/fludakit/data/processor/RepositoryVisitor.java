package io.github.fludakit.data.processor;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.processor.emitter.FindEmitter;
import io.github.fludakit.data.processor.emitter.HydrationEmitter;
import io.github.fludakit.data.processor.emitter.LifecycleEmitter;
import io.github.fludakit.data.processor.emitter.MetamodelEmitter;
import io.github.fludakit.data.processor.emitter.RowMapperEmitter;
import io.github.fludakit.data.processor.emitter.TypeModelEmitter;
import io.github.fludakit.data.processor.emitter.WriteEmitter;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.qbn.ParsedMethodName;
import io.github.fludakit.data.qbn.QueryByMethodNameParser;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.LikePattern;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;
import jakarta.data.repository.By;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Find;
import jakarta.data.repository.Insert;
import jakarta.data.repository.OrderBy;
import jakarta.data.repository.Param;
import jakarta.data.repository.Query;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;

import javax.annotation.processing.Filer;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Visits a single {@code @Repository}-annotated interface, resolves its entity type and methods,
 * and generates the skeleton {@code Repository_} class.
 */
final class RepositoryVisitor {

    private static final String CRUD_REPOSITORY = "jakarta.data.repository.CrudRepository";
    private static final String BASIC_REPOSITORY = "jakarta.data.repository.BasicRepository";
    private static final String DATA_REPOSITORY = "jakarta.data.repository.DataRepository";
    private static final String JDBC_CLIENT = "io.github.fludakit.jdbc.JdbcClient";
    private static final String PAGE_REQUEST = "jakarta.data.page.PageRequest";
    private static final String PAGE = "jakarta.data.page.Page";
    private static final String CURSORED_PAGE = "jakarta.data.page.CursoredPage";

    // QBN module is optional — check if it's on the classpath
    private static final boolean QBN_AVAILABLE = isQbnAvailable();

    private static boolean isQbnAvailable() {
        try {
            Class.forName("io.github.fludakit.data.qbn.QueryByMethodNameParser");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Pattern matching named parameters in @Query SQL (e.g. {@code :name}). */
    private static final Pattern NAMED_PARAM_PATTERN = Pattern.compile(":([a-zA-Z_]\\w*)");

    private final TypeElement repository;
    private final RepositoryProcessor host;
    private final Types types;
    private final Elements elements;
    private final Diagnostics diagnostics;
    private final Filer filer;
    private final TypeMappingVisitor typeMappingVisitor;

    /** Method contexts built during visit — consumed by later emitter tasks. */
    private final List<MethodContext> methods = new ArrayList<>();

    /** JdbcClient accessor methods that need a pass-through override. */
    private final List<ExecutableElement> jdbcClientAccessors = new ArrayList<>();

    RepositoryVisitor(TypeElement repository, RepositoryProcessor host) {
        this.repository = repository;
        this.host = host;
        this.types = host.types();
        this.elements = host.elements();
        this.diagnostics = host.diagnostics();
        this.filer = host.filer();
        this.typeMappingVisitor = host.typeMappingVisitor();
    }

    void visit() {
        // 1. Resolve entity type from generic superclass
        TypeMirror entityTypeMirror = resolveEntityType();
        if (entityTypeMirror == null) {
            diagnostics.error("Cannot resolve entity type for repository " + repository.getQualifiedName(),
                    repository);
            return;
        }

        String entityFqn = types.erasure(entityTypeMirror).toString();
        TypeElement entityElement = asTypeElement(entityTypeMirror);
        if (entityElement == null) {
            diagnostics.error("Entity type is not a declared type: " + entityFqn, repository);
            return;
        }

        // 2. Fetch TypeDefinition from TypeMappingVisitor
        TypeDefinition entityMapping = typeMappingVisitor.get(entityFqn);
        if (entityMapping == null) {
            diagnostics.error("No @Entity/@AggregateRoot/@ValueObject mapping found for " + entityFqn,
                    repository);
            return;
        }

        // 3. Determine repository flavour
        boolean crud = extendsInterface(CRUD_REPOSITORY);

        // 4. Generate row mapper (or null if not eligible)
        RowMapperEmitter rowMapperEmitter = new RowMapperEmitter(types, elements, filer);
        ClassName rowMapperType = rowMapperEmitter.emit(entityMapping);
        if (rowMapperType == null) {
            // Fallback placeholder for entities with references/collections (not row-mappable)
            rowMapperType = ClassName.bestGuess(entityFqn + "RowMapper");
        }

        // 4a. Generate row mappers for VO types used in element collections
        // Note: ElementCollection stores element type FQN, not a TypeDefinition
        // Row mappers for element types are generated when scanning the VO itself

        // 4b. Generate static metamodel (_Entity interface + navigable member classes)
        MetamodelEmitter metamodelEmitter = new MetamodelEmitter(filer, diagnostics);

        // Pre-claim all entity metamodel FQNs so collision detection works across entities
        for (String managedEntityFqn : typeMappingVisitor.managedEntities()) {
            TypeDefinition managedMapping = typeMappingVisitor.get(managedEntityFqn);
            if (managedMapping != null) {
                metamodelEmitter.preclaimEntityMetamodel(managedMapping);
            }
        }

        metamodelEmitter.emit(entityMapping);

        // 4c. Generate TypeModel (runtime TypeDefinition implementation)
        new TypeModelEmitter(filer).emit(entityMapping);

        // 5. Create RepositoryContext
        RepositoryContext ctx = new RepositoryContext(
                entityMapping, crud, rowMapperType,
                repository.getQualifiedName().toString());

        // 6. Iterate abstract methods
        for (javax.lang.model.element.Element member : elements.getAllMembers(repository)) {
            if (member.getKind() != javax.lang.model.element.ElementKind.METHOD) continue;
            ExecutableElement method = (ExecutableElement) member;

            // Skip default methods
            if (method.isDefault()) continue;

            // Skip non-abstract methods (concrete inherited from interfaces)
            if (!method.getModifiers().contains(Modifier.ABSTRACT)) continue;

            // Skip methods declared on java.lang.Object
            if (isObjectMethod(method)) continue;

            // Handle JdbcClient accessor methods
            if (isJdbcClientAccessor(method)) {
                jdbcClientAccessors.add(method);
                continue;
            }

            // Handle @Query-annotated methods
            if (method.getAnnotation(Query.class) != null) {
                MethodContext queryCtx = resolveQueryMethod(method);
                if (queryCtx != null) {
                    methods.add(queryCtx);
                }
                continue;
            }

            MethodContext methodCtx = resolveMethod(method, entityMapping);
            if (methodCtx != null) {
                methods.add(methodCtx);
            } else {
                // Method could not be resolved — throw error suggesting @Query
                String methodName = method.getSimpleName().toString();
                String repoName = repository.getQualifiedName().toString();
                throw new IllegalStateException(
                    "Cannot resolve method '" + methodName + "' in repository " +
                    repoName + ". Either add a lifecycle annotation " +
                    "(@Find, @Save, @Insert, @Update, @Delete), use a recognized CRUD method name " +
                    "(findById, save, etc.), use Query By Name (findByXxx), or add a custom @Query annotation."
                );
            }
        }

        // 6b. Emit plain finder methods
        DeclaredType repositoryDeclaredType = resolveRepositoryDeclaredType();
        DeclaredType repositoryOwnType = (DeclaredType) repository.asType();
        FindEmitter findEmitter = new FindEmitter(types, repositoryDeclaredType, repositoryOwnType);
        for (MethodContext mc : methods) {
            MethodSpec emitted = findEmitter.emit(ctx, mc);
            if (emitted != null) {
                ctx.auxMethods().add(emitted);
            }
        }

        // 6c. Emit batch lifecycle methods (saveAll, deleteAll, deleteByIds)
        LifecycleEmitter lifecycleEmitter = new LifecycleEmitter(types, elements, repositoryDeclaredType, repositoryOwnType);
        for (MethodContext mc : methods) {
            MethodSpec emitted = lifecycleEmitter.emit(ctx, mc);
            if (emitted != null) {
                ctx.auxMethods().add(emitted);
            }
        }

        // 6d. Emit write methods (save, insert, update, deleteById)
        WriteEmitter writeEmitter = new WriteEmitter(types, elements, repositoryDeclaredType, repositoryOwnType);
        for (MethodContext mc : methods) {
            MethodSpec emitted = writeEmitter.emit(ctx, mc);
            if (emitted != null) {
                ctx.auxMethods().add(emitted);
            }
        }

        // 6e. Emit hydration methods for entities with to-many collections
        HydrationEmitter hydrationEmitter = new HydrationEmitter();
        if (hydrationEmitter.hasCollectionsToHydrate(entityMapping)) {
            MethodSpec hydrateMethod = hydrationEmitter.emitHydrate(ctx);
            if (hydrateMethod != null) {
                ctx.auxMethods().add(hydrateMethod);
            }
            MethodSpec hydrateListMethod = hydrationEmitter.emitHydrateList(ctx);
            if (hydrateListMethod != null) {
                ctx.auxMethods().add(hydrateListMethod);
            }
            List<MethodSpec> loaders = hydrationEmitter.emitCollectionLoaders(ctx);
            ctx.auxMethods().addAll(loaders);
        }

        // 6f. Emit QueryModel (static QueryDefinition fields)

        // 7. Generate skeleton Repository_ class
        emitSkeleton(ctx);
    }

    // ── Entity type resolution ───────────────────────────────────────────

    /**
     * Resolves the entity type from the repository's generic superclass.
     * Walks the interface hierarchy to find CrudRepository, BasicRepository, or DataRepository
     * and extracts the first type argument.
     */
    private TypeMirror resolveEntityType() {
        // Try superclass first (extends CrudRepository<E, K>)
        TypeMirror superclass = repository.getSuperclass();
        TypeMirror entityType = extractEntityTypeArg(superclass);
        if (entityType != null) return entityType;

        // Try interfaces
        for (TypeMirror iface : repository.getInterfaces()) {
            entityType = extractEntityTypeArg(iface);
            if (entityType != null) return entityType;
        }

        // Walk transitive interfaces
        for (TypeMirror iface : getAllInterfaces(repository)) {
            entityType = extractEntityTypeArg(iface);
            if (entityType != null) return entityType;
        }

        return null;
    }

    /**
     * If the given type is a parameterized CrudRepository/BasicRepository/DataRepository,
     * extracts the first type argument (the entity type).
     */
    private TypeMirror extractEntityTypeArg(TypeMirror type) {
        if (type == null || type.getKind() != TypeKind.DECLARED) return null;
        DeclaredType dt = (DeclaredType) type;
        TypeElement te = (TypeElement) dt.asElement();
        String fqn = te.getQualifiedName().toString();

        if (fqn.equals(CRUD_REPOSITORY) || fqn.equals(BASIC_REPOSITORY) || fqn.equals(DATA_REPOSITORY)) {
            List<? extends TypeMirror> typeArgs = dt.getTypeArguments();
            if (!typeArgs.isEmpty()) {
                return typeArgs.getFirst();
            }
        }
        return null;
    }

    private List<TypeMirror> getAllInterfaces(TypeElement type) {
        List<TypeMirror> result = new ArrayList<>();
        for (TypeMirror iface : type.getInterfaces()) {
            result.add(iface);
            TypeElement ifaceElement = asTypeElement(iface);
            if (ifaceElement != null) {
                result.addAll(getAllInterfaces(ifaceElement));
            }
        }
        TypeMirror superclass = type.getSuperclass();
        TypeElement superElement = asTypeElement(superclass);
        if (superElement != null && !superElement.getQualifiedName().contentEquals("java.lang.Object")) {
            result.addAll(getAllInterfaces(superElement));
        }
        return result;
    }

    // ── Repository flavour detection ─────────────────────────────────────

    private boolean extendsInterface(String fqn) {
        for (TypeMirror iface : repository.getInterfaces()) {
            if (iface.getKind() == TypeKind.DECLARED) {
                TypeElement te = (TypeElement) ((DeclaredType) iface).asElement();
                if (te.getQualifiedName().contentEquals(fqn)) return true;
            }
        }
        // Check transitive interfaces
        for (TypeMirror iface : getAllInterfaces(repository)) {
            if (iface.getKind() == TypeKind.DECLARED) {
                TypeElement te = (TypeElement) ((DeclaredType) iface).asElement();
                if (te.getQualifiedName().contentEquals(fqn)) return true;
            }
        }
        return false;
    }

    // ── Method resolution ────────────────────────────────────────────────

    private MethodContext resolveMethod(ExecutableElement method, TypeDefinition entityMapping) {
        String methodName = method.getSimpleName().toString();

        // Priority 1: Lifecycle annotations (@Save, @Insert, @Update, @Delete, @Find)
        // These are first-class citizens and can appear on any interface method
        Operation annotatedOp = resolveLifecycleAnnotation(method);
        if (annotatedOp != null) {
            return resolveAnnotatedMethod(method, methodName, annotatedOp, entityMapping);
        }

        // Priority 2 & 3 only apply to Jakarta Data-derived repositories
        boolean isJakartaDataRepository = extendsJakartaDataInterface();

        // Priority 2: Built-in CRUD methods from Jakarta Data standard interfaces
        // Fixed list with known semantics — skip annotation processing
        if (isJakartaDataRepository && isBuiltInCrudMethod(methodName)) {
            Operation op = resolveBuiltInOperation(methodName);
            return resolveBuiltInCrudMethod(method, methodName, op, entityMapping);
        }

        // Priority 3: Query By Name (QBN) parsing
        if (isJakartaDataRepository) {
            ParsedMethodName parsed = tryParseQbn(methodName);
            if (parsed != null) {
                return resolveQbnMethod(method, methodName, parsed, entityMapping);
            }
        }

        // Not a recognized method — skip
        return null;
    }

    /**
     * Checks if the repository extends a Jakarta Data interface (CrudRepository, BasicRepository, or DataRepository).
     */
    private boolean extendsJakartaDataInterface() {
        // Check superclass
        if (isJakartaDataInterface(repository.getSuperclass())) return true;

        // Check direct interfaces
        for (TypeMirror iface : repository.getInterfaces()) {
            if (isJakartaDataInterface(iface)) return true;
        }

        // Check transitive interfaces
        for (TypeMirror iface : getAllInterfaces(repository)) {
            if (isJakartaDataInterface(iface)) return true;
        }

        return false;
    }

    private boolean isJakartaDataInterface(TypeMirror type) {
        if (type == null || type.getKind() != TypeKind.DECLARED) return false;
        TypeElement te = (TypeElement) ((DeclaredType) type).asElement();
        String fqn = te.getQualifiedName().toString();
        return fqn.equals(CRUD_REPOSITORY) ||
               fqn.equals("jakarta.data.repository.BasicRepository") ||
               fqn.equals(DATA_REPOSITORY);
    }

    private Operation resolveLifecycleAnnotation(ExecutableElement method) {
        if (method.getAnnotation(Save.class) != null) return Operation.SAVE;
        if (method.getAnnotation(Insert.class) != null) return Operation.INSERT;
        if (method.getAnnotation(Update.class) != null) return Operation.UPDATE;
        if (method.getAnnotation(Delete.class) != null) return Operation.DELETE;
        if (method.getAnnotation(Find.class) != null) return Operation.FIND;
        return null;
    }

    private MethodContext resolveAnnotatedMethod(ExecutableElement method, String methodName,
                                                  Operation operation, TypeDefinition entityMapping) {
        // Validate that write operations accept entity or entity collection parameters
        if (operation == Operation.SAVE || operation == Operation.INSERT || operation == Operation.UPDATE || operation == Operation.DELETE) {
            if (!method.getParameters().isEmpty()) {
                VariableElement firstParam = method.getParameters().getFirst();
                String paramType = firstParam.asType().toString();
                String entityType = entityMapping.typeName();
                
                // Check if parameter is the entity type, a generic type variable (S extends Entity), 
                // or a collection of entities (including generic collections like List<S>, List<? extends T>)
                boolean isValid = paramType.equals(entityType) 
                        || paramType.startsWith("java.util.List<" + entityType)
                        || paramType.startsWith("java.util.Collection<" + entityType)
                        // Allow generic type parameters (e.g., <S extends Entity> S save(S entity))
                        || (paramType.length() == 1 && Character.isUpperCase(paramType.charAt(0)))
                        // Allow collections of generic type parameters (e.g., List<S>, List<? extends T>)
                        || paramType.matches("java\\.util\\.(List|Collection)<\\??\\s*(extends\\s+)?[A-Z]>");
                
                if (!isValid) {
                    throw new IllegalArgumentException(
                            "Method " + methodName + " carries a lifecycle annotation and must accept "
                            + "the repository entity type (" + entityType + ") or a collection thereof, not " + paramType);
                }
            }
        }
        
        ResultKind resultKind = resolveResultKind(method, operation);
        PagingMode pagingMode = resolvePagingMode(method, resultKind);
        List<Predicate> predicates = resolveByAnnotations(method, entityMapping);

        // Replace Jakarta Data's "id(this)" JDQL convention with the actual ID property name
        predicates = replaceIdThis(predicates, entityMapping);

        List<SortSpec> sorts = resolveOrderByAnnotations(method);
        List<ParamBinding> paramBindings = buildParamBindings(method, predicates);
        boolean distinct = false;

        return new MethodContext(method, methodName, operation, resultKind, distinct,
                method.getParameters(), paramBindings, predicates, sorts, pagingMode, null);
    }

    private boolean isBuiltInCrudMethod(String methodName) {
        return switch (methodName) {
            case "findById", "deleteById", "findAll", "save", "saveAll",
                 "insert", "insertAll", "update", "updateAll",
                 "delete", "deleteAll", "deleteAllById", "deleteByIds",
                 "findAllById" -> true;
            default -> false;
        };
    }

    private Operation resolveBuiltInOperation(String methodName) {
        return switch (methodName) {
            case "save", "saveAll" -> Operation.SAVE;
            case "insert", "insertAll" -> Operation.INSERT;
            case "update", "updateAll" -> Operation.UPDATE;
            case "delete", "deleteAll", "deleteById", "deleteAllById", "deleteByIds" -> Operation.DELETE;
            case "findById", "findAll", "findAllById" -> Operation.FIND;
            default -> throw new IllegalStateException("Not a built-in CRUD method: " + methodName);
        };
    }

    private MethodContext resolveBuiltInCrudMethod(ExecutableElement method, String methodName,
                                                    Operation operation, TypeDefinition entityMapping) {
        ResultKind resultKind = resolveResultKind(method, operation);
        PagingMode pagingMode = resolvePagingMode(method, resultKind);
        boolean distinct = false;

        // Create predicates based on method name
        List<Predicate> predicates = switch (methodName) {
            case "findById", "deleteById" -> {
                if (entityMapping.idColumn() == null) {
                    yield List.of();
                }
                String idProp = idPropertyName(entityMapping);
                yield List.of(Predicate.of(idProp, io.github.fludakit.data.query.Operator.EQ, "id"));
            }
            default -> List.of();
        };

        List<SortSpec> sorts = List.of();
        List<ParamBinding> paramBindings = buildParamBindings(method, predicates);

        return new MethodContext(method, methodName, operation, resultKind, distinct,
                method.getParameters(), paramBindings, predicates, sorts, pagingMode, null);
    }

    private MethodContext resolveQbnMethod(ExecutableElement method, String methodName,
                                            ParsedMethodName parsed, TypeDefinition entityMapping) {
        Operation operation = parsed.operation();
        ResultKind resultKind = resolveResultKind(method, operation);
        PagingMode pagingMode = resolvePagingMode(method, resultKind);
        boolean distinct = parsed.distinct();

        // Predicates from parsed method name
        List<Predicate> predicates = new ArrayList<>(parsed.predicates());
        // Resolve camelCase paths against entity structure (e.g., "workHistoryCompany" → "workHistory.company")
        predicates = resolveCamelCasePaths(predicates, entityMapping);
        // Add @By predicates if present (these supplement QBN predicates)
        predicates.addAll(resolveByAnnotations(method, entityMapping));

        // Sorts from parsed method name + @OrderBy
        List<SortSpec> sorts = new ArrayList<>(parsed.sorts());
        sorts.addAll(resolveOrderByAnnotations(method));

        List<ParamBinding> paramBindings = buildParamBindings(method, predicates);

        return new MethodContext(method, methodName, operation, resultKind, distinct,
                method.getParameters(), paramBindings, predicates, sorts, pagingMode, null);
    }

    /**
     * Attempts to resolve camelCase property paths by splitting them at entity property boundaries.
     * For example, "workHistoryCompany" becomes "workHistory.company" if "workHistory" is a property.
     */
    private List<Predicate> resolveCamelCasePaths(List<Predicate> predicates, TypeDefinition entityMapping) {
        List<Predicate> resolved = new ArrayList<>(predicates.size());
        for (Predicate p : predicates) {
            String path = p.property();
            if (path.contains(".")) {
                resolved.add(p);
                continue;
            }
            // Try to split the camelCase path
            String resolvedPath = trySplitCamelCasePath(path, entityMapping);
            if (resolvedPath != null && !resolvedPath.equals(path)) {
                // When a camelCase path is resolved to a dotted path, the param name should be the
                // leaf property name (e.g., "orderItemsQuantity" → "orderItems.quantity" → param "quantity").
                // This matches the Java parameter name convention.
                String leafPropName = resolvedPath.contains(".")
                        ? resolvedPath.substring(resolvedPath.lastIndexOf('.') + 1)
                        : resolvedPath;
                resolved.add(new Predicate(resolvedPath, p.operator(), leafPropName, p.connector(), p.ignoreCase()));
            } else {
                resolved.add(p);
            }
        }
        return resolved;
    }

    /**
     * Tries to split a camelCase path at entity property boundaries.
     * Returns the dot-separated path if successful, or the original path if no split is found.
     */
    private String trySplitCamelCasePath(String path, TypeDefinition mapping) {
        // Try splitting at each uppercase letter
        for (int i = 1; i < path.length(); i++) {
            if (Character.isUpperCase(path.charAt(i))) {
                String head = path.substring(0, i);
                String tail = Character.toLowerCase(path.charAt(i)) + path.substring(i + 1);
                // Check if head is a known property
                TypeDefinition.Prop prop = mapping.findProperty(head);
                if (prop != null) {
                    // head is a property, check if tail resolves in the definition
                    String fullPath = head + "." + tail;
                    
                    // Special handling for ElementCollection - can't use resolve() directly
                    if (prop instanceof TypeDefinition.Prop.ElementCollection ec) {
                        TypeDefinition elementType = typeMappingVisitor.get(ec.javaType());
                        if (elementType != null && elementType.findProperty(tail) != null) {
                            return fullPath;
                        }
                        // If element type not found in cache yet, assume path is valid
                        // and let SQL generation handle it with EXISTS subquery
                        return fullPath;
                    } else {
                        try {
                            mapping.resolve(fullPath);
                            return fullPath;
                        } catch (Exception e) {
                            // tail doesn't resolve, try deeper splits
                            TypeDefinition targetMapping = getTargetMapping(mapping, head);
                            if (targetMapping != null) {
                                String resolvedTail = trySplitCamelCasePath(tail, targetMapping);
                                if (resolvedTail != null && !resolvedTail.equals(tail)) {
                                    return head + "." + resolvedTail;
                                }
                            }
                        }
                    }
                }
            }
        }
        return path;
    }

    /**
     * Gets the definition TypeDefinition for a property name, or null if not resolvable.
     */
    private TypeDefinition getTargetMapping(TypeDefinition mapping, String propName) {
        TypeDefinition.Prop prop = mapping.findProperty(propName);
        if (prop instanceof TypeDefinition.Prop.Association assoc) return assoc.definition();
        if (prop instanceof TypeDefinition.Prop.EntityCollection ec) return ec.definition();
        if (prop instanceof TypeDefinition.Prop.Embedded emb) return emb.definition();
        if (prop instanceof TypeDefinition.Prop.ElementCollection ec) {
            // For element collections, look up the element type definition from cache
            return typeMappingVisitor.get(ec.javaType());
        }
        return null;
    }

    private ParsedMethodName tryParseQbn(String methodName) {
        if (!QBN_AVAILABLE) {
            return null;
        }
        try {
            return new QueryByMethodNameParser().parse(methodName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── ResultKind resolution ────────────────────────────────────────────

    private ResultKind resolveResultKind(ExecutableElement method, Operation operation) {
        TypeMirror returnType = method.getReturnType();

        // Void
        if (returnType.getKind() == TypeKind.VOID) return ResultKind.VOID;

        // Primitives
        if (returnType.getKind() == TypeKind.LONG || returnType.getKind() == TypeKind.INT) {
            return switch (operation) {
                case DELETE -> ResultKind.AFFECTED;
                case COUNT -> ResultKind.COUNT;
                default -> ResultKind.AFFECTED;
            };
        }
        if (returnType.getKind() == TypeKind.BOOLEAN) {
            return operation == Operation.EXISTS ? ResultKind.EXISTS : ResultKind.SINGLE;
        }

        String returnFqn = erasureFqn(returnType);

        // Optional<E>
        if ("java.util.Optional".equals(returnFqn)) return ResultKind.OPTIONAL;

        // List<E> / Collection<E> / Iterable<E>
        if ("java.util.List".equals(returnFqn) || "java.util.Collection".equals(returnFqn)
                || "java.lang.Iterable".equals(returnFqn)) return ResultKind.LIST;

        // Set<E>
        if ("java.util.Set".equals(returnFqn)) return ResultKind.SET;

        // Stream<E>
        if ("java.util.stream.Stream".equals(returnFqn)) return ResultKind.STREAM;

        // CursoredPage<E>
        if (PAGE.equals(returnFqn) || CURSORED_PAGE.equals(returnFqn)) {
            if (CURSORED_PAGE.equals(returnFqn)) return ResultKind.CURSORED_PAGE;
            return ResultKind.PAGE;
        }

        // Array E[]
        if (returnType.getKind() == TypeKind.ARRAY) return ResultKind.ARRAY;

        // Default: single entity
        return ResultKind.SINGLE;
    }

    // ── PagingMode resolution ────────────────────────────────────────────

    private PagingMode resolvePagingMode(ExecutableElement method, ResultKind resultKind) {
        for (VariableElement param : method.getParameters()) {
            String paramFqn = erasureFqn(param.asType());
            if (PAGE_REQUEST.equals(paramFqn)) {
                // Determine OFFSET vs CURSOR based on return type
                if (resultKind == ResultKind.CURSORED_PAGE) return PagingMode.CURSOR;
                return PagingMode.OFFSET;
            }
        }
        return PagingMode.NONE;
    }

    // ── Predicate resolution ─────────────────────────────────────────────

    /**
     * Resolves predicates from {@code @By} annotations on method parameters.
     */
    private List<Predicate> resolveByAnnotations(ExecutableElement method, TypeDefinition entityMapping) {
        List<Predicate> predicates = new ArrayList<>();
        for (VariableElement param : method.getParameters()) {
            By byAnnotation = param.getAnnotation(By.class);
            if (byAnnotation != null) {
                String propertyPath = byAnnotation.value();
                String paramName = param.getSimpleName().toString();
                predicates.add(Predicate.of(propertyPath, io.github.fludakit.data.query.Operator.EQ, paramName));
            }
        }
        return predicates;
    }

    /**
     * Replaces Jakarta Data's {@code "id(this)"} JDQL convention with the entity's actual ID property name.
     * This processor does not implement JDQL, so {@code "id(this)"} is replaced with the real column.
     */
    private List<Predicate> replaceIdThis(List<Predicate> predicates, TypeDefinition entityMapping) {
        if (predicates.isEmpty() || entityMapping.idColumn() == null) {
            return predicates;
        }
        String idPropName = idPropertyName(entityMapping);
        List<Predicate> result = new ArrayList<>(predicates.size());
        for (Predicate p : predicates) {
            if ("id(this)".equals(p.property())) {
                result.add(Predicate.of(idPropName, p.operator(), p.param(), p.connector(), p.ignoreCase()));
            } else {
                result.add(p);
            }
        }
        return result;
    }

    // ── Sort resolution ──────────────────────────────────────────────────

    /**
     * Resolves sorts from {@code @OrderBy} annotations on the method.
     */
    private List<SortSpec> resolveOrderByAnnotations(ExecutableElement method) {
        List<SortSpec> sorts = new ArrayList<>();
        OrderBy[] orderBys = method.getAnnotationsByType(OrderBy.class);
        for (OrderBy orderBy : orderBys) {
            String value = orderBy.value();
            // Parse "property ASC" or "property DESC" or just "property"
            String[] parts = value.trim().split("\\s+");
            String property = parts[0];
            boolean ascending = parts.length < 2 || !"DESC".equalsIgnoreCase(parts[1]);
            sorts.add(SortSpec.of(property, ascending, false));
        }
        return sorts;
    }

    // ── ParamBinding resolution ──────────────────────────────────────────

    /**
     * Builds param bindings by matching method parameters to predicates.
     */
    private List<ParamBinding> buildParamBindings(ExecutableElement method, List<Predicate> predicates) {
        List<ParamBinding> bindings = new ArrayList<>();
        List<? extends VariableElement> params = method.getParameters();

        // Count non-paging parameters
        List<Integer> nonPagingParamIndices = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            VariableElement param = params.get(i);
            String paramFqn = erasureFqn(param.asType());
            if (!PAGE_REQUEST.equals(paramFqn)) {
                nonPagingParamIndices.add(i);
            }
        }

        // Map each predicate to its binding slot(s)
        int slotIndex = 0;
        for (Predicate pred : predicates) {
            Operator op = pred.operator();

            if (op == Operator.BETWEEN || op == Operator.NOT_BETWEEN) {
                // BETWEEN needs TWO consecutive parameter slots -> _from and _to
                if (slotIndex + 1 < nonPagingParamIndices.size()) {
                    int fromParamIdx = nonPagingParamIndices.get(slotIndex);
                    int toParamIdx = nonPagingParamIndices.get(slotIndex + 1);
                    bindings.add(new ParamBinding(fromParamIdx, pred.param() + "_from", LikePattern.NONE));
                    bindings.add(new ParamBinding(toParamIdx, pred.param() + "_to", LikePattern.NONE));
                    slotIndex += 2; // Consumed two parameter slots
                }
            } else {
                // Regular operator: one parameter slot
                if (slotIndex < nonPagingParamIndices.size()) {
                    int paramIdx = nonPagingParamIndices.get(slotIndex);
                    bindings.add(new ParamBinding(paramIdx, pred.param(), LikePattern.of(op)));
                    slotIndex++;
                }
            }
        }

        return bindings;
    }

    // ── JdbcClient accessor detection ────────────────────────────────────

    private boolean isJdbcClientAccessor(ExecutableElement method) {
        String returnFqn = erasureFqn(method.getReturnType());
        if (!JDBC_CLIENT.equals(returnFqn)) return false;
        if (!method.getParameters().isEmpty()) return false;
        String name = method.getSimpleName().toString();
        return name.startsWith("get") || name.startsWith("jdbcClient");
    }

    // ── @Query method resolution ─────────────────────────────────────────

    /**
     * Resolves a {@code @Query}-annotated method into a {@link MethodContext}.
     * Extracts the raw SQL, determines the operation type, builds param bindings
     * from {@code @Param} annotations or parameter names matching named parameters
     * in the SQL.
     */
    private MethodContext resolveQueryMethod(ExecutableElement method) {
        Query query = method.getAnnotation(Query.class);
        if (query == null) return null;

        String sql = query.value();
        String methodName = method.getSimpleName().toString();

        // Determine operation: DELETE SQL → DELETE, otherwise FIND
        Operation operation = sql.trim().toUpperCase().startsWith("DELETE")
                ? Operation.DELETE : Operation.FIND;

        // Determine result kind from return type
        ResultKind resultKind = resolveResultKind(method, operation);

        // Extract named parameters from SQL
        List<String> namedParams = extractNamedParams(sql);

        // Build param bindings matching method parameters to named SQL parameters
        List<ParamBinding> bindings = buildQueryBindings(method, namedParams);

        return new MethodContext(method, methodName, operation, resultKind, false,
                method.getParameters(), bindings, List.of(), List.of(), PagingMode.NONE, sql);
    }

    /**
     * Extracts unique named parameter names from SQL in order of first appearance.
     * Matches patterns like {@code :name} while ignoring string literals.
     */
    private List<String> extractNamedParams(String sql) {
        List<String> params = new ArrayList<>();
        Matcher matcher = NAMED_PARAM_PATTERN.matcher(sql);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!params.contains(name)) {
                params.add(name);
            }
        }
        return params;
    }

    /**
     * Builds {@link ParamBinding}s for a {@code @Query} method by matching method parameters
     * to named SQL parameters. Uses {@code @Param} annotation value if present, otherwise
     * falls back to the parameter name.
     */
    private List<ParamBinding> buildQueryBindings(ExecutableElement method, List<String> namedParams) {
        List<ParamBinding> bindings = new ArrayList<>();
        List<? extends VariableElement> params = method.getParameters();

        for (int i = 0; i < params.size(); i++) {
            VariableElement param = params.get(i);
            String paramFqn = erasureFqn(param.asType());

            // Skip paging parameters
            if (PAGE_REQUEST.equals(paramFqn)) continue;

            // Determine the SQL parameter name: @Param value or parameter name
            String paramName;
            Param paramAnnotation = param.getAnnotation(Param.class);
            if (paramAnnotation != null) {
                paramName = paramAnnotation.value();
            } else {
                paramName = param.getSimpleName().toString();
            }

            // Only bind if this name appears in the SQL named parameters
            if (namedParams.contains(paramName)) {
                bindings.add(new ParamBinding(i, paramName, LikePattern.NONE));
            }
        }
        return bindings;
    }

    // ── Skeleton emission ────────────────────────────────────────────────

    private void emitSkeleton(RepositoryContext ctx) {
        String repoSimpleName = repository.getSimpleName().toString();
        ClassName repoClassName = ClassName.get(repository);
        ClassName generatedName = ClassName.get(
                repoClassName.packageName(),
                repoSimpleName + "_");

        ClassName jdbcClientClass = ClassName.bestGuess(JDBC_CLIENT);
        ClassName applicationScoped = ClassName.bestGuess("jakarta.enterprise.context.ApplicationScoped");
        ClassName inject = ClassName.bestGuess("jakarta.inject.Inject");

        TypeSpec.Builder shell = TypeSpec.classBuilder(generatedName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(applicationScoped)
                .addSuperinterface(repoClassName)
                .addField(FieldSpec.builder(jdbcClientClass, "jdbcClient",
                                Modifier.PRIVATE, Modifier.FINAL)
                        .build())
                .addMethod(MethodSpec.constructorBuilder()
                        .addAnnotation(inject)
                        .addParameter(jdbcClientClass, "jdbcClient")
                        .addStatement("this.jdbcClient = jdbcClient")
                        .build());

        // Add SQL constants as public static final fields
        for (Map.Entry<String, String> entry : ctx.constants().entrySet()) {
            shell.addField(FieldSpec.builder(String.class, entry.getKey(),
                            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$S", entry.getValue())
                    .build());
        }

        // Add generated method overrides
        for (MethodSpec auxMethod : ctx.auxMethods()) {
            shell.addMethod(auxMethod);
        }

        // Add JdbcClient accessor overrides
        for (ExecutableElement accessor : jdbcClientAccessors) {
            shell.addMethod(MethodSpec.overriding(accessor)
                    .addStatement("return this.$L", "jdbcClient")
                    .build());
        }

        try {
            JavaFile.builder(repoClassName.packageName(), shell.build())
                    .build()
                    .writeTo(filer);
        } catch (IOException e) {
            diagnostics.error("Failed to write " + generatedName + ": " + e.getMessage(), repository);
        }
    }

    /**
     * Resolves the parameterized CrudRepository/BasicRepository/DataRepository type
     * from the repository's type hierarchy. This is needed for proper type variable
     * resolution when overriding methods like save(T entity) — we need the concrete
     * type arguments (e.g., CrudRepository<Customer, Long>) not just the repository
     * interface itself.
     */
    private DeclaredType resolveRepositoryDeclaredType() {
        // Check superclass first (e.g. TaskRepository extends CrudRepository<Task, Long>)
        TypeMirror superclass = repository.getSuperclass();
        DeclaredType found = matchBaseRepositoryType(superclass);
        if (found != null) return found;

        // Check direct interfaces
        for (TypeMirror iface : repository.getInterfaces()) {
            found = matchBaseRepositoryType(iface);
            if (found != null) return found;
        }

        // Walk transitive interfaces
        for (TypeMirror iface : getAllInterfaces(repository)) {
            found = matchBaseRepositoryType(iface);
            if (found != null) return found;
        }

        // Fallback to the repository's own type if no base repository found
        return (DeclaredType) repository.asType();
    }

    private DeclaredType matchBaseRepositoryType(TypeMirror type) {
        if (type == null || type.getKind() != TypeKind.DECLARED) return null;
        DeclaredType dt = (DeclaredType) type;
        TypeElement te = (TypeElement) dt.asElement();
        String fqn = te.getQualifiedName().toString();
        if (fqn.equals(CRUD_REPOSITORY) || fqn.equals(BASIC_REPOSITORY) || fqn.equals(DATA_REPOSITORY)) {
            return dt;
        }
        return null;
    }

    // ── Utility methods ──────────────────────────────────────────────────

    private TypeElement asTypeElement(TypeMirror type) {
        if (type == null || type.getKind() != TypeKind.DECLARED) return null;
        javax.lang.model.element.Element element = ((DeclaredType) type).asElement();
        return element instanceof TypeElement te ? te : null;
    }

    private String erasureFqn(TypeMirror type) {
        if (type == null) return "";
        if (type.getKind().isPrimitive()) return type.toString();
        if (type.getKind() == TypeKind.ARRAY) return "array";
        return types.erasure(type).toString();
    }

    private boolean isObjectMethod(ExecutableElement method) {
        String name = method.getSimpleName().toString();
        List<? extends VariableElement> params = method.getParameters();
        // Common Object methods: equals(Object), hashCode(), toString()
        if ("equals".equals(name) && params.size() == 1) return true;
        if ("hashCode".equals(name) && params.isEmpty()) return true;
        if ("toString".equals(name) && params.isEmpty()) return true;
        return false;
    }

    /**
     * Returns the Java property name of the primary key column from a TypeDefinition.
     */
    private static String idPropertyName(TypeDefinition type) {
        if (type instanceof TypeDefinition.Entity e) return e.id().name();
        if (type instanceof TypeDefinition.Aggregate a) return a.id().name();
        return null;
    }
}
