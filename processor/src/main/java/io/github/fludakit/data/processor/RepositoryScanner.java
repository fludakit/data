package io.github.fludakit.data.processor;

import com.squareup.javapoet.ClassName;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.metadata.UnknownAttributePathException;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.processor.context.MethodContext.Operation;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.writer.RowMapperWriter;
import io.github.fludakit.data.qbn.Action;
import io.github.fludakit.data.qbn.ParsedMethodName;
import io.github.fludakit.data.qbn.QueryByMethodNameParser;
import io.github.fludakit.data.query.LikePattern;
import io.github.fludakit.data.query.LimitSpec;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Order;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;
import io.github.fludakit.data.query.Window;

import jakarta.data.repository.By;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Find;
import jakarta.data.repository.Insert;
import jakarta.data.repository.OrderBy;
import jakarta.data.repository.Param;
import jakarta.data.repository.Query;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans {@code @Repository}-annotated interfaces against the shared {@link TypeGraph}: resolves
 * the entity mapping and method contexts into a {@link RepositoryContext}, and a whole round of
 * repositories into one {@link RepositoryGraph}.
 *
 * <p>Resolution only — graph writers and method emitters are owned by {@link RepositoryProcessor}.</p>
 */
final class RepositoryScanner {

    private static final String CRUD_REPOSITORY = "jakarta.data.repository.CrudRepository";
    private static final String BASIC_REPOSITORY = "jakarta.data.repository.BasicRepository";
    private static final String DATA_REPOSITORY = "jakarta.data.repository.DataRepository";
    private static final String JDBC_CLIENT = "io.github.fludakit.jdbc.JdbcClient";
    private static final String PAGE_REQUEST = "jakarta.data.page.PageRequest";
    private static final String PAGE = "jakarta.data.page.Page";
    private static final String CURSORED_PAGE = "jakarta.data.page.CursoredPage";
    private static final String LIMIT = "jakarta.data.Limit";
    private static final String SORT = "jakarta.data.Sort";
    private static final String ORDER = "jakarta.data.Order";

    // QBN module is optional — check if it's on the classpath
    private static final boolean QBN_AVAILABLE = isQbnAvailable();

    private final Types types;
    private final Elements elements;
    private final Diagnostics diagnostics;
    private final TypeGraph typeGraph;
    private final RepositoryGraph repositoryGraph;
    private final RepositoryHierarchy hierarchy;
    private final ParamBinder paramBinder;
    private final QbnResolver qbn;
    private final NativeQueryResolver nativeQueries;

    RepositoryScanner(Types types, Elements elements, Diagnostics diagnostics, TypeGraph typeGraph,
                      RepositoryGraph repositoryGraph) {
        this.types = types;
        this.elements = elements;
        this.diagnostics = diagnostics;
        this.typeGraph = typeGraph;
        this.repositoryGraph = repositoryGraph;
        this.hierarchy = new RepositoryHierarchy(types, typeGraph, diagnostics);
        this.paramBinder = new ParamBinder(types);
        this.qbn = new QbnResolver(typeGraph, hierarchy);
        this.nativeQueries = new NativeQueryResolver(paramBinder);
    }

    private static boolean isQbnAvailable() {
        try {
            Class.forName("io.github.fludakit.data.qbn.QueryByMethodNameParser");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Scans every repository of the round into the shared {@link RepositoryGraph}, keyed by
     * repository FQN.
     *
     * <p>A repository that cannot be resolved is reported and left out of the graph; the other
     * repositories of the round are unaffected.</p>
     */
    void scanRepositories(List<TypeElement> repositories) {
        for (TypeElement repository : repositories) {
            try {
                RepositoryContext context = resolveContext(repository);
                if (context != null) {
                    repositoryGraph.put(repository, context);
                }
            } catch (RuntimeException e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                diagnostics.error(
                        "fluda-data could not generate " + repository.getSimpleName() + "_: " + msg,
                        repository);
            }
        }
    }

    /**
     * Resolves one repository into its {@link RepositoryContext}: the entity mapping from
     * {@link TypeGraph}, the conventional row-mapper type name, base repository type for overrides,
     * and method contexts for every resolvable repository method.
     */
    private RepositoryContext resolveContext(TypeElement repository) {
        // 1–2. Resolve the entity type from the repository hierarchy and look it up in the type
        // graph; any failure is already reported on the repository.
        TypeDefinition entityMapping = hierarchy.resolveEntityMapping(repository);
        if (entityMapping == null) {
            return null;
        }
        String entityFqn = entityMapping.typeName();

        // 3. Bind the conventional row-mapper type (written earlier from TypeGraph by the host)
        ClassName rowMapperType = RowMapperWriter.rowMapperClassName(entityFqn);

        // 4. Scan methods, then freeze the plan into a RepositoryContext
        MethodScan scan = scanMethods(repository, entityMapping);
        return new RepositoryContext(
                entityMapping, rowMapperType, hierarchy.resolveRepositoryDeclaredType(repository),
                scan.methods(), scan.jdbcClientAccessor());
    }

    // ── Method resolution ────────────────────────────────────────────────

    private record MethodScan(List<MethodContext> methods, ExecutableElement jdbcClientAccessor) {}

    private MethodScan scanMethods(TypeElement repository, TypeDefinition entityMapping) {
        List<MethodContext> methods = new ArrayList<>();
        ExecutableElement jdbcClientAccessor = null;
        for (javax.lang.model.element.Element member : elements.getAllMembers(repository)) {
            if (member.getKind() != ElementKind.METHOD) continue;
            ExecutableElement method = (ExecutableElement) member;

            // Skip default methods
            if (method.isDefault()) continue;

            // Skip non-abstract methods (concrete inherited from interfaces)
            if (!method.getModifiers().contains(Modifier.ABSTRACT)) continue;

            // Skip methods declared on java.lang.Object
            if (isObjectMethod(method)) continue;

            // Handle the optional JdbcClient escape hatch: JdbcClient getJdbcClient()
            if (isJdbcClientAccessor(method)) {
                jdbcClientAccessor = method;
                continue;
            }

            // Fail early, per method: an unexpected resolution failure is reported on the method
            // element and only that method is skipped (AGENTS.md "Fail early, per method").
            MethodContext methodCtx;
            try {
                // Handle @Query-annotated methods
                if (method.getAnnotation(Query.class) != null) {
                    MethodContext queryCtx = resolveQueryMethod(method);
                    if (queryCtx != null) {
                        methods.add(queryCtx);
                    }
                    continue;
                }
                methodCtx = resolveMethod(repository, method, entityMapping);
            } catch (RuntimeException e) {
                diagnostics.error("Cannot resolve method '" + method.getSimpleName() + "': "
                        + messageOf(e), method);
                continue;
            }
            // A null context has already been reported on the method (see resolveMethod): skip it only.
            if (methodCtx != null) {
                methods.add(methodCtx);
            }
        }
        return new MethodScan(methods, jdbcClientAccessor);
    }

    /**
     * Resolves one abstract repository method: lifecycle annotation first, then a built-in CRUD name,
     * then a query by method name.
     *
     * @return the method context, or {@code null} when the method is skipped; a located diagnostic has
     *         then already been reported on it
     */
    private MethodContext resolveMethod(TypeElement repository, ExecutableElement method,
                                       TypeDefinition entityMapping) {
        String methodName = method.getSimpleName().toString();

        // Priority 1: Lifecycle annotations (@Save, @Insert, @Update, @Delete, @Find)
        // These are first-class citizens and can appear on any interface method
        Operation annotated = resolveLifecycleAnnotation(method);
        if (annotated != null) {
            return resolveAnnotatedMethod(method, methodName, annotated, entityMapping);
        }

        // Priority 2 & 3 only apply to Jakarta Data-derived repositories
        boolean isJakartaDataRepository = hierarchy.extendsJakartaDataInterface(repository);

        // Priority 2: Built-in CRUD methods from Jakarta Data standard interfaces
        // Fixed list with known semantics — skip annotation processing
        if (isJakartaDataRepository && isBuiltInCrudMethod(methodName)) {
            Operation operation = resolveBuiltInOperation(methodName);
            return resolveBuiltInCrudMethod(method, methodName, operation, entityMapping);
        }

        // Priority 3: Query By Name (QBN) parsing
        if (isJakartaDataRepository) {
            ParsedMethodName parsed = qbn.tryParseQbn(methodName);
            if (parsed != null) {
                return resolveQbnMethod(method, methodName, parsed, entityMapping);
            }
        }

        // Not a recognized method: report it on the method, which is then skipped
        String qbnError = qbn.qbnParseError(repository, methodName);
        if (qbnError != null) {
            diagnostics.error("Cannot resolve method '" + methodName
                    + "' as a query by method name: " + qbnError, method);
        } else {
            diagnostics.error(
                "Cannot resolve method '" + methodName + "' in repository " +
                repository.getQualifiedName() + ". Either add a lifecycle annotation " +
                "(@Find, @Save, @Insert, @Update, @Delete), use a recognized CRUD method name " +
                "(findById, save, etc.), use Query By Name (findByXxx), or add a custom @Query annotation.",
                method);
        }
        return null;
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
        boolean lifecycleEntityDelete = operation == Operation.DELETE
                && isLifecycleEntityParameter(method, entityMapping);

        // SAVE/INSERT/UPDATE always take the entity (or collection). DELETE does when the
        // first parameter is the entity; otherwise it is a parameter-based automatic query (#51).
        if (operation == Operation.SAVE || operation == Operation.INSERT || operation == Operation.UPDATE
                || lifecycleEntityDelete) {
            if (!method.getParameters().isEmpty() && !isLifecycleEntityParameter(method, entityMapping)) {
                VariableElement firstParam = method.getParameters().getFirst();
                diagnostics.error(
                        "Method " + methodName + " carries a lifecycle annotation and must accept "
                        + "the repository entity type (" + entityMapping.typeName()
                        + ") or a collection thereof, not " + firstParam.asType(),
                        method);
                return null;
            }
        }

        ResultKind resultKind = resolveResultKind(method, operation);
        List<Predicate> predicates;
        if (operation == Operation.FIND || (operation == Operation.DELETE && !lifecycleEntityDelete)) {
            predicates = resolveAutomaticQueryPredicates(method, entityMapping);
            if (predicates == null) {
                return null; // unknown attribute already diagnosed
            }
        } else {
            predicates = resolveByAnnotations(method);
        }

        // Replace Jakarta Data's "id(this)" JDQL convention with the actual ID property name
        predicates = replaceIdThis(predicates, entityMapping);

        List<SortSpec> sorts = resolveOrderByAnnotations(method);
        List<ParamBinding> paramBindings = paramBinder.buildParamBindings(method, predicates);

        return methodContext(method, methodName, operation,
                buildQuery(operation, resultKind, false, predicates, paramBindings, sorts, 0, method));
    }

    /** True when the first parameter is the entity (or a collection thereof) for lifecycle writes. */
    private boolean isLifecycleEntityParameter(ExecutableElement method, TypeDefinition entityMapping) {
        if (method.getParameters().isEmpty()) return false;
        String paramType = method.getParameters().getFirst().asType().toString();
        String entityType = entityMapping.typeName();
        return paramType.equals(entityType)
                || paramType.startsWith("java.util.List<" + entityType)
                || paramType.startsWith("java.util.Collection<" + entityType)
                || (paramType.length() == 1 && Character.isUpperCase(paramType.charAt(0)))
                || paramType.matches("java\\.util\\.(List|Collection)<\\??\\s*(extends\\s+)?[A-Z]>");
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

        // Create predicates based on method name
        List<Predicate> predicates = switch (methodName) {
            case "findById", "deleteById" -> {
                if (entityMapping.idColumn() == null) {
                    yield List.of();
                }
                String idProp = entityMapping.id().name();
                yield List.of(Predicate.of(idProp, io.github.fludakit.data.query.Operator.EQ, "id"));
            }
            default -> List.of();
        };

        List<ParamBinding> paramBindings = paramBinder.buildParamBindings(method, predicates);

        return methodContext(method, methodName, operation,
                buildQuery(operation, resultKind, false, predicates, paramBindings, List.of(), 0, method));
    }

    private MethodContext resolveQbnMethod(ExecutableElement method, String methodName,
                                            ParsedMethodName parsed, TypeDefinition entityMapping) {
        // First<n> is a static limit: a Limit or PageRequest parameter would be a second row window,
        // and Jakarta Data requires the combination to be rejected rather than one of them to win.
        String pagingParameter = parsed.limit() > 0 ? pagingParameterType(method) : null;
        if (pagingParameter != null) {
            diagnostics.error("Method " + methodName + " combines the First keyword with a " + pagingParameter
                    + " parameter, which Jakarta Data does not allow: First is already a static limit. Remove"
                    + " First or the " + pagingParameter + " parameter.", method);
            return null;
        }
        Operation operation = QbnResolver.operationFromAction(parsed.action());
        ResultKind resultKind = resolveResultKind(method, operation);
        boolean distinct = parsed.distinct();

        // Predicates from parsed method name
        List<Predicate> predicates = new ArrayList<>(parsed.predicates());
        // Resolve camelCase paths against entity structure (e.g., "workHistoryCompany" → "workHistory.company")
        predicates = qbn.resolveCamelCasePaths(predicates, entityMapping);
        // Add @By predicates if present (these supplement QBN predicates)
        predicates.addAll(resolveByAnnotations(method));

        // Sorts from parsed method name + @OrderBy
        List<SortSpec> sorts = new ArrayList<>(parsed.sorts());
        sorts.addAll(resolveOrderByAnnotations(method));

        List<ParamBinding> paramBindings = paramBinder.buildParamBindings(method, predicates);

        return methodContext(method, methodName, operation,
                buildQuery(operation, resultKind, distinct, predicates, paramBindings, sorts, parsed.limit(),
                        method));
    }

    private static String messageOf(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getName();
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

        String returnFqn = erasureFqn(types, returnType);

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

    // ── Predicate resolution ─────────────────────────────────────────────

    /**
     * Resolves predicates for {@code @Find} / parameter-based {@code @Delete}: omitted {@code @By}
     * uses the Java parameter name ({@code -parameters}, {@code _} → {@code .}); present {@code @By}
     * uses its required {@code value()}.
     *
     * @return the predicates, or {@code null} when an unknown attribute was diagnosed
     */
    private List<Predicate> resolveAutomaticQueryPredicates(ExecutableElement method,
                                                            TypeDefinition entityMapping) {
        List<Predicate> predicates = new ArrayList<>();
        for (VariableElement param : method.getParameters()) {
            if (paramBinder.isSpecialQueryParameter(param)) {
                continue;
            }
            String paramName = param.getSimpleName().toString();
            String propertyPath = resolveByPropertyPath(param);

            if (!"id(this)".equals(propertyPath)) {
                try {
                    entityMapping.resolve(propertyPath);
                } catch (UnknownAttributePathException e) {
                    diagnostics.error(
                            "Unknown entity attribute '" + propertyPath + "' for parameter '"
                                    + paramName + "' on " + entityMapping.typeName()
                                    + ". Annotate with @By(\"...\") or compile with -parameters so the "
                                    + "parameter name matches an attribute.",
                            param);
                    return null;
                }
            }
            predicates.add(Predicate.of(propertyPath, Operator.EQ, paramName));
        }
        return predicates;
    }

    /**
     * Resolves predicates from explicit {@code @By} annotations only (QBN / lifecycle paths).
     */
    private List<Predicate> resolveByAnnotations(ExecutableElement method) {
        List<Predicate> predicates = new ArrayList<>();
        for (VariableElement param : method.getParameters()) {
            By byAnnotation = param.getAnnotation(By.class);
            if (byAnnotation != null) {
                String paramName = param.getSimpleName().toString();
                predicates.add(Predicate.of(byAnnotation.value(), Operator.EQ, paramName));
            }
        }
        return predicates;
    }

    /**
     * Entity attribute path: no {@code @By} → Java parameter name ({@code _} → {@code .});
     * with {@code @By} → its required {@code value()} (no default in jakarta.data-api).
     */
    private static String resolveByPropertyPath(VariableElement param) {
        By byAnnotation = param.getAnnotation(By.class);
        if (byAnnotation != null) {
            return byAnnotation.value();
        }
        return param.getSimpleName().toString().replace('_', '.');
    }

    /**
     * Replaces Jakarta Data's {@code "id(this)"} JDQL convention with the entity's actual ID property name.
     * This processor does not implement JDQL, so {@code "id(this)"} is replaced with the real column.
     */
    private List<Predicate> replaceIdThis(List<Predicate> predicates, TypeDefinition entityMapping) {
        if (predicates.isEmpty() || entityMapping.idColumn() == null) {
            return predicates;
        }
        String idPropName = entityMapping.id().name();
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

    // ── JdbcClient accessor detection ────────────────────────────────────

    /** The one supported escape hatch: {@code JdbcClient getJdbcClient()} — optional, at most one. */
    private boolean isJdbcClientAccessor(ExecutableElement method) {
        return method.getSimpleName().contentEquals("getJdbcClient")
                && JDBC_CLIENT.equals(erasureFqn(types, method.getReturnType()))
                && method.getParameters().isEmpty();
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

        Operation operation = sql.trim().toUpperCase().startsWith("DELETE") ? Operation.DELETE : Operation.FIND;
        ResultKind resultKind = resolveResultKind(method, operation);

        // Extract named parameters from SQL
        List<String> namedParams = nativeQueries.extractNamedParams(sql);

        // Build param bindings matching method parameters to named SQL parameters
        List<ParamBinding> bindings = nativeQueries.buildQueryBindings(method, namedParams);

        return methodContext(method, methodName, operation, new QueryDefinition.Native(sql, resultKind, bindings));
    }

    // ── QueryDefinition assembly ─────────────────────────────────────────

    private MethodContext methodContext(ExecutableElement method, String methodName, Operation operation, QueryDefinition query) {
        return new MethodContext(method, methodName, operation, query);
    }

    /**
     * Builds the pure query plan. {@link Operation#SAVE} maps to {@link QueryDefinition.Upsert};
     * criteria intents carry predicates/bindings/order/window.
     *
     * @param firstLimit the {@code First<n>} row cap of a query by method name, {@code 0} for none. Only a
     *        find carries one: the grammar has no {@code First} on count, exists or delete.
     */
    private QueryDefinition buildQuery(Operation operation, ResultKind resultKind, boolean distinct,
                                       List<Predicate> predicates, List<ParamBinding> bindings,
                                       List<SortSpec> sorts, int firstLimit, ExecutableElement method) {
        return switch (operation) {
            case FIND -> new QueryDefinition.Read(
                    resultKind, predicates, bindings, distinct,
                    resolveOrder(method, sorts), resolveWindow(method, resultKind, firstLimit));
            case COUNT -> new QueryDefinition.Count(predicates, bindings, distinct);
            case EXISTS -> new QueryDefinition.Exists(predicates, bindings);
            case DELETE -> predicates.isEmpty()
                    ? new QueryDefinition.DeleteById(resultKind)
                    : new QueryDefinition.DeleteWhere(predicates, bindings, resultKind);
            case INSERT -> new QueryDefinition.Insert(resultKind, true);
            case UPDATE -> new QueryDefinition.Update(resultKind);
            case SAVE -> new QueryDefinition.Upsert(resultKind);
        };
    }

    private Order resolveOrder(ExecutableElement method, List<SortSpec> sorts) {
        List<? extends VariableElement> params = method.getParameters();
        for (int i = 0; i < params.size(); i++) {
            String typeFqn = params.get(i).asType().toString();
            if (typeFqn.contains("jakarta.data.Order") || typeFqn.contains("jakarta.data.Sort")) {
                return new Order.Dynamic(i);
            }
        }
        if (sorts == null || sorts.isEmpty()) {
            return Order.None.INSTANCE;
        }
        return Order.Static.of(sorts);
    }

    /**
     * The row window: a {@code PageRequest} or {@code Limit} parameter, else the static {@code firstLimit}
     * cap ({@code 0} for none). A method combining the two is rejected before this is reached.
     */
    private Window resolveWindow(ExecutableElement method, ResultKind resultKind, int firstLimit) {
        List<? extends VariableElement> params = method.getParameters();
        for (int i = 0; i < params.size(); i++) {
            String paramFqn = erasureFqn(types, params.get(i).asType());
            if (PAGE_REQUEST.equals(paramFqn)) {
                return resultKind == ResultKind.CURSORED_PAGE
                        ? new Window.Cursor(i)
                        : new Window.Offset(i);
            }
            if ("jakarta.data.Limit".equals(paramFqn)) {
                return new Window.Limit(i);
            }
        }
        return new Window.None(new LimitSpec(firstLimit, 0));
    }

    /** {@code "PageRequest"} or {@code "Limit"} for the first such parameter of {@code method}, else {@code null}. */
    private String pagingParameterType(ExecutableElement method) {
        for (VariableElement param : method.getParameters()) {
            String paramFqn = erasureFqn(types, param.asType());
            if (PAGE_REQUEST.equals(paramFqn) || LIMIT.equals(paramFqn)) {
                return paramFqn.substring(paramFqn.lastIndexOf('.') + 1);
            }
        }
        return null;
    }

    // ── Utility methods ──────────────────────────────────────────────────

    private static String erasureFqn(Types types, TypeMirror type) {
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

    // ── Nested helpers ─────────────────────────────────────────────────────────────────────────

    /**
     * Repository interface-hierarchy walks: the superclass first, then the direct interfaces, then
     * every transitive interface (depth-first, see {@link #getAllInterfaces}), stopping at the first
     * type a matcher accepts, and resolves the repository's entity mapping from the {@link TypeGraph}.
     */
    private static final class RepositoryHierarchy {

        private final Types types;
        private final TypeGraph typeGraph;
        private final Diagnostics diagnostics;

        RepositoryHierarchy(Types types, TypeGraph typeGraph, Diagnostics diagnostics) {
            this.types = types;
            this.typeGraph = typeGraph;
            this.diagnostics = diagnostics;
        }

        /**
         * The repository's entity mapping: the entity type argument of its CrudRepository /
         * BasicRepository / DataRepository base, looked up in the {@link TypeGraph}. Every repository
         * entity must be annotated (@Entity/@AggregateRoot/@ValueObject) so the type scan has already
         * registered it; if the type cannot be resolved or is missing from the graph, the error is
         * reported on the repository and {@code null} is returned.
         */
        TypeDefinition resolveEntityMapping(TypeElement repository) {
            TypeMirror entityTypeMirror = firstInHierarchy(repository, this::extractEntityTypeArg);
            if (entityTypeMirror == null) {
                diagnostics.error("Cannot resolve entity type for repository " + repository.getQualifiedName(),
                        repository);
                return null;
            }

            String entityFqn = types.erasure(entityTypeMirror).toString();
            if (asTypeElement(entityTypeMirror) == null) {
                diagnostics.error("Entity type is not a declared type: " + entityFqn, repository);
                return null;
            }

            TypeDefinition entityMapping = typeGraph.get(entityFqn);
            if (entityMapping == null) {
                diagnostics.error("No @Entity/@AggregateRoot/@ValueObject mapping found for " + entityFqn,
                        repository);
                return null;
            }
            return entityMapping;
        }

        /**
         * Resolves the parameterized CrudRepository/BasicRepository/DataRepository type
         * from the repository's type hierarchy. This is needed for proper type variable
         * resolution when overriding methods like save(T entity) — we need the concrete
         * type arguments (e.g., CrudRepository<Customer, Long>) not just the repository
         * interface itself.
         */
        DeclaredType resolveRepositoryDeclaredType(TypeElement repository) {
            DeclaredType found = firstInHierarchy(repository, this::matchBaseRepositoryType);
            if (found != null) return found;

            // Fallback to the repository's own type if no base repository found
            return (DeclaredType) repository.asType();
        }

        /**
         * Checks if the repository extends a Jakarta Data interface (CrudRepository, BasicRepository, or DataRepository).
         */
        boolean extendsJakartaDataInterface(TypeElement repository) {
            return firstInHierarchy(repository, type -> isJakartaDataInterface(type) ? type : null) != null;
        }

        /**
         * The single hierarchy walk shared by {@link #resolveEntityMapping}, {@link #resolveRepositoryDeclaredType}
         * and {@link #extendsJakartaDataInterface}: the first non-null {@code match} result, trying the
         * superclass, then the direct interfaces, then the transitive interfaces; {@code null} if none match.
         */
        private <T> T firstInHierarchy(TypeElement repository, Function<TypeMirror, T> match) {
            // Try superclass first (e.g. TaskRepository extends CrudRepository<Task, Long>)
            T found = match.apply(repository.getSuperclass());
            if (found != null) return found;

            // Try direct interfaces
            for (TypeMirror iface : repository.getInterfaces()) {
                found = match.apply(iface);
                if (found != null) return found;
            }

            // Walk transitive interfaces
            for (TypeMirror iface : getAllInterfaces(repository)) {
                found = match.apply(iface);
                if (found != null) return found;
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

        private boolean isJakartaDataInterface(TypeMirror type) {
            if (type == null || type.getKind() != TypeKind.DECLARED) return false;
            TypeElement te = (TypeElement) ((DeclaredType) type).asElement();
            String fqn = te.getQualifiedName().toString();
            return fqn.equals(CRUD_REPOSITORY) ||
                   fqn.equals("jakarta.data.repository.BasicRepository") ||
                   fqn.equals(DATA_REPOSITORY);
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

        TypeElement asTypeElement(TypeMirror type) {
            if (type == null || type.getKind() != TypeKind.DECLARED) return null;
            javax.lang.model.element.Element element = ((DeclaredType) type).asElement();
            return element instanceof TypeElement te ? te : null;
        }
    }

    /**
     * Query-by-method-name support: parsing (when the optional QBN module is on the classpath) and
     * resolution of camelCase property paths against the entity structure.
     */
    private static final class QbnResolver {

        private final TypeGraph typeGraph;
        private final RepositoryHierarchy hierarchy;

        QbnResolver(TypeGraph typeGraph, RepositoryHierarchy hierarchy) {
            this.typeGraph = typeGraph;
            this.hierarchy = hierarchy;
        }

        ParsedMethodName tryParseQbn(String methodName) {
            if (!QBN_AVAILABLE) {
                return null;
            }
            try {
                return new QueryByMethodNameParser().parse(methodName);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /**
         * When {@code methodName} looks like a query by method name (findBy..., countBy..., ...) on a
         * Jakarta Data repository but does not parse, the parser's reason; otherwise {@code null}.
         */
        String qbnParseError(TypeElement repository, String methodName) {
            if (!QBN_AVAILABLE || !hierarchy.extendsJakartaDataInterface(repository)
                    || !methodName.matches("(find|count|exists|delete)\\w*By[A-Z]\\w*")) {
                return null;
            }
            try {
                new QueryByMethodNameParser().parse(methodName);
                return null;
            } catch (RuntimeException e) {
                return messageOf(e);
            }
        }

        static Operation operationFromAction(Action action) {
            return switch (action) {
                case FIND -> Operation.FIND;
                case DELETE -> Operation.DELETE;
                case COUNT -> Operation.COUNT;
                case EXISTS -> Operation.EXISTS;
            };
        }

        /**
         * Attempts to resolve camelCase property paths by splitting them at entity property boundaries.
         * For example, "workHistoryCompany" becomes "workHistory.company" if "workHistory" is a property.
         */
        List<Predicate> resolveCamelCasePaths(List<Predicate> predicates, TypeDefinition entityMapping) {
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
                    
                        // Special handling for ElementCollection - can't use resolve() directly: assume the
                        // path is valid and let SQL generation handle it with EXISTS subquery
                        if (prop instanceof TypeDefinition.Prop.ElementCollection) {
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
            return switch (prop) {
                case null -> null;
                case TypeDefinition.Prop.Association assoc -> assoc.definition();
                case TypeDefinition.Prop.EntityCollection ec -> ec.definition();
                case TypeDefinition.Prop.Embedded emb -> emb.definition();
                // For element collections, look up the element type definition from the type graph
                case TypeDefinition.Prop.ElementCollection ec -> typeGraph.get(ec.javaType());
                case TypeDefinition.Prop.Scalar ignored -> null;
                case TypeDefinition.Prop.Id ignored -> null;
                case TypeDefinition.Prop.Version ignored -> null;
            };
        }
    }

    /**
     * {@code @Query} support: named-parameter extraction from the raw SQL and binding of method
     * parameters to those names.
     */
    private static final class NativeQueryResolver {

        /** Pattern matching named parameters in @Query SQL (e.g. {@code :name}). */
        private static final Pattern NAMED_PARAM_PATTERN = Pattern.compile(":([a-zA-Z_]\\w*)");

        private final ParamBinder paramBinder;

        NativeQueryResolver(ParamBinder paramBinder) {
            this.paramBinder = paramBinder;
        }

        /**
         * Extracts unique named parameter names from SQL in order of first appearance.
         * Matches patterns like {@code :name} while ignoring string literals.
         */
        List<String> extractNamedParams(String sql) {
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
         * to named SQL parameters. No {@code @Param} → Java parameter name; with {@code @Param} →
         * its required {@code value()} (no default in jakarta.data-api).
         */
        List<ParamBinding> buildQueryBindings(ExecutableElement method, List<String> namedParams) {
            List<ParamBinding> bindings = new ArrayList<>();
            List<? extends VariableElement> params = method.getParameters();

            for (int i = 0; i < params.size(); i++) {
                VariableElement param = params.get(i);
                // Skip Limit / Sort / Order / PageRequest
                if (paramBinder.isSpecialQueryParameter(param)) continue;

                String paramName = resolveQueryParamName(param);

                // Only bind if this name appears in the SQL named parameters
                if (namedParams.contains(paramName)) {
                    bindings.add(new ParamBinding(i, paramName, LikePattern.NONE));
                }
            }
            return bindings;
        }

        /**
         * Named SQL parameter: no {@code @Param} → Java parameter name; with {@code @Param} →
         * its required {@code value()}.
         */
        private static String resolveQueryParamName(VariableElement param) {
            Param paramAnnotation = param.getAnnotation(Param.class);
            if (paramAnnotation != null) {
                return paramAnnotation.value();
            }
            return param.getSimpleName().toString();
        }
    }

    /**
     * Binds method parameters to predicate slots, skipping the special query parameters
     * (PageRequest, Limit, Sort, Order).
     */
    private static final class ParamBinder {

        private final Types types;

        ParamBinder(Types types) {
            this.types = types;
        }

        /**
         * Builds param bindings by matching method parameters to predicates.
         */
        List<ParamBinding> buildParamBindings(ExecutableElement method, List<Predicate> predicates) {
            List<ParamBinding> bindings = new ArrayList<>();
            List<? extends VariableElement> params = method.getParameters();

            // Attribute / query value parameters only (skip Limit, Sort, Order, PageRequest)
            List<Integer> nonPagingParamIndices = new ArrayList<>();
            for (int i = 0; i < params.size(); i++) {
                if (!isSpecialQueryParameter(params.get(i))) {
                    nonPagingParamIndices.add(i);
                }
            }

            // Every predicate must be fully bindable; a half-bound plan compiles but fails at runtime.
            // Operator owns the bind-arity table (nullary operators bind no value).
            int requiredSlots = 0;
            for (Predicate pred : predicates) {
                requiredSlots += pred.operator().parameterCount();
            }
            if (requiredSlots > nonPagingParamIndices.size()) {
                throw new IllegalArgumentException("method '" + method.getSimpleName() + "' requires "
                        + requiredSlots + " query parameter(s) but declares " + nonPagingParamIndices.size());
            }

            // Map each predicate to its binding slot(s)
            int slotIndex = 0;
            for (Predicate pred : predicates) {
                int slots = pred.operator().parameterCount();
                if (slots == 0) {
                    continue;
                }
                if (slots == 2) {
                    // BETWEEN needs TWO consecutive parameter slots -> _from and _to
                    int fromParamIdx = nonPagingParamIndices.get(slotIndex);
                    int toParamIdx = nonPagingParamIndices.get(slotIndex + 1);
                    bindings.add(new ParamBinding(fromParamIdx, pred.param() + "_from", LikePattern.NONE));
                    bindings.add(new ParamBinding(toParamIdx, pred.param() + "_to", LikePattern.NONE));
                } else {
                    int paramIdx = nonPagingParamIndices.get(slotIndex);
                    bindings.add(new ParamBinding(paramIdx, pred.param(), LikePattern.of(pred.operator())));
                }
                slotIndex += slots;
            }

            return bindings;
        }

        boolean isSpecialQueryParameter(VariableElement param) {
            String fqn = erasureFqn(types, param.asType());
            return PAGE_REQUEST.equals(fqn)
                    || LIMIT.equals(fqn)
                    || SORT.equals(fqn)
                    || ORDER.equals(fqn)
                    || fqn.startsWith(SORT + "<")
                    || fqn.startsWith(ORDER + "<");
        }
    }
}
