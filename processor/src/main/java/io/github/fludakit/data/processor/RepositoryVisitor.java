package io.github.fludakit.data.processor;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.processor.emitter.FindEmitter;
import io.github.fludakit.data.processor.emitter.HydrationEmitter;
import io.github.fludakit.data.processor.emitter.LifecycleEmitter;
import io.github.fludakit.data.processor.emitter.MetamodelEmitter;
import io.github.fludakit.data.processor.emitter.QueryModelEmitter;
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

        // 2. Fetch TypeMapping from TypeMappingVisitor
        TypeMapping<?> entityMapping = typeMappingVisitor.get(entityFqn);
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

        // 4b. Generate static metamodel (_Entity interface + navigable member classes)
        MetamodelEmitter metamodelEmitter = new MetamodelEmitter(filer, diagnostics);

        // Pre-claim all entity metamodel FQNs so collision detection works across entities
        for (String managedEntityFqn : typeMappingVisitor.managedEntities()) {
            TypeMapping<?> managedMapping = typeMappingVisitor.get(managedEntityFqn);
            if (managedMapping != null) {
                metamodelEmitter.preclaimEntityMetamodel(managedMapping);
            }
        }

        metamodelEmitter.emit(entityMapping);

        // 4c. Generate TypeModel (runtime TypeMapping implementation)
        new TypeModelEmitter(filer).emit(entityMapping);

        // 5. Create RepositoryContext
        RepositoryContext ctx = new RepositoryContext(
                entityMapping, crud, false, rowMapperType,
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
            }
        }

        // 6b. Emit plain finder methods
        FindEmitter findEmitter = new FindEmitter();
        for (MethodContext mc : methods) {
            MethodSpec emitted = findEmitter.emit(ctx, mc);
            if (emitted != null) {
                ctx.auxMethods().add(emitted);
            }
        }

        // 6c. Emit batch lifecycle methods (saveAll, deleteAll, deleteByIds)
        LifecycleEmitter lifecycleEmitter = new LifecycleEmitter();
        for (MethodContext mc : methods) {
            MethodSpec emitted = lifecycleEmitter.emit(ctx, mc);
            if (emitted != null) {
                ctx.auxMethods().add(emitted);
            }
        }

        // 6d. Emit write methods (save, insert, update, deleteById)
        WriteEmitter writeEmitter = new WriteEmitter();
        for (MethodContext mc : methods) {
            MethodSpec emitted = writeEmitter.emit(ctx, mc);
            if (emitted != null) {
                ctx.auxMethods().add(emitted);
            }
        }

        // 6e. Emit hydration methods for aggregate roots
        if (entityMapping.ownsAggregateLifecycle()) {
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
        }

        // 6f. Emit QueryModel (static QueryDefinition fields)
        new QueryModelEmitter(filer).emit(repository, ctx, methods);

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
                return typeArgs.get(0);
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

    private MethodContext resolveMethod(ExecutableElement method, TypeMapping<?> entityMapping) {
        String methodName = method.getSimpleName().toString();

        // Determine operation from annotations
        Operation operation = resolveOperation(method, methodName);
        if (operation == null) {
            // Try QBN parsing
            ParsedMethodName parsed = tryParseQbn(methodName);
            if (parsed != null) {
                return resolveQbnMethod(method, methodName, parsed, entityMapping);
            }
            // Not a recognized method — skip
            return null;
        }

        // Annotated method (non-QBN)
        ResultKind resultKind = resolveResultKind(method, operation);
        PagingMode pagingMode = resolvePagingMode(method, resultKind);
        List<Predicate> predicates = resolveByAnnotations(method, entityMapping);
        List<SortSpec> sorts = resolveOrderByAnnotations(method);
        List<ParamBinding> paramBindings = buildParamBindings(method, predicates);
        boolean distinct = false;

        return new MethodContext(method, methodName, operation, resultKind, distinct,
                method.getParameters(), paramBindings, predicates, sorts, pagingMode, null);
    }

    private MethodContext resolveQbnMethod(ExecutableElement method, String methodName,
                                            ParsedMethodName parsed, TypeMapping<?> entityMapping) {
        Operation operation = parsed.operation();
        ResultKind resultKind = resolveResultKind(method, operation);
        PagingMode pagingMode = resolvePagingMode(method, resultKind);
        boolean distinct = parsed.distinct();

        // Predicates from parsed method name
        List<Predicate> predicates = new ArrayList<>(parsed.predicates());
        // Add @By predicates if present (these supplement QBN predicates)
        predicates.addAll(resolveByAnnotations(method, entityMapping));

        // Sorts from parsed method name + @OrderBy
        List<SortSpec> sorts = new ArrayList<>(parsed.sorts());
        sorts.addAll(resolveOrderByAnnotations(method));

        List<ParamBinding> paramBindings = buildParamBindings(method, predicates);

        return new MethodContext(method, methodName, operation, resultKind, distinct,
                method.getParameters(), paramBindings, predicates, sorts, pagingMode, null);
    }

    private ParsedMethodName tryParseQbn(String methodName) {
        try {
            return new QueryByMethodNameParser().parse(methodName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── Operation resolution ─────────────────────────────────────────────

    private Operation resolveOperation(ExecutableElement method, String methodName) {
        if (method.getAnnotation(Save.class) != null) return Operation.SAVE;
        if (method.getAnnotation(Insert.class) != null) return Operation.INSERT;
        if (method.getAnnotation(Update.class) != null) return Operation.UPDATE;
        if (method.getAnnotation(Delete.class) != null) return Operation.DELETE;
        if (method.getAnnotation(Find.class) != null) return Operation.FIND;

        // CRUD method name conventions
        return switch (methodName) {
            case "save", "saveAll" -> Operation.SAVE;
            case "insert", "insertAll" -> Operation.INSERT;
            case "update", "updateAll" -> Operation.UPDATE;
            case "delete", "deleteAll", "deleteById", "deleteAllById", "deleteByIds" -> Operation.DELETE;
            case "findById", "findAll", "findAllById" -> Operation.FIND;
            default -> null;
        };
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
    private List<Predicate> resolveByAnnotations(ExecutableElement method, TypeMapping<?> entityMapping) {
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

        // Track which parameters are paging params (skip them)
        int predicateParamIndex = 0;
        for (int i = 0; i < params.size(); i++) {
            VariableElement param = params.get(i);
            String paramFqn = erasureFqn(param.asType());

            // Skip paging parameters
            if (PAGE_REQUEST.equals(paramFqn)) continue;

            // @By parameters bind by name; advance index since the @By predicate
            // occupies a slot in the combined predicate list
            By byAnnotation = param.getAnnotation(By.class);
            if (byAnnotation != null) {
                String paramName = param.getSimpleName().toString();
                for (Predicate pred : predicates) {
                    if (pred.param().equals(paramName)) {
                        bindings.add(new ParamBinding(i, paramName, LikePattern.of(pred.operator())));
                        break;
                    }
                }
                predicateParamIndex++;
                continue;
            }

            // Match positional parameters to predicates
            if (predicateParamIndex < predicates.size()) {
                Predicate pred = predicates.get(predicateParamIndex);
                bindings.add(new ParamBinding(i, pred.param(), LikePattern.of(pred.operator())));
                predicateParamIndex++;
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
}
