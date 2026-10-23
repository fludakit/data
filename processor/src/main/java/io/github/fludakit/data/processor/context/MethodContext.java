package io.github.fludakit.data.processor.context;

import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MethodContext {
    private final ExecutableElement method;
    private final String name;
    private final Operation operation;
    private final ResultKind resultKind;
    private final boolean distinct;
    private final List<? extends VariableElement> parameters;
    private final List<ParamBinding> paramBindings;
    private final List<Predicate> predicates;
    private final List<SortSpec> sorts;
    private final PagingMode pagingMode;
    private final String customSql;

    // Query-specific navigation decisions resolved during predicate/sort analysis
    private final Map<String, NavigationDecision> navigationDecisions;
    private TypeSnapshot typeSnapshot;

    public record NavigationDecision(
        String propertyPath,
        NavigationMode mode, // FULL_ENTITY, FK_ONLY
        boolean requiresJoin,
        boolean backReference
    ) {}

    public enum NavigationMode {
        FULL_ENTITY,  // Fetch complete entity (used in SELECT/FETCH)
        FK_ONLY       // Fetch only FK column (used in WHERE predicates)
    }

    public record TypeSnapshot(
        TypeDefinition rootType,
        Map<String, NavigationDecision> navigationDecisions
    ) {}

    public MethodContext(ExecutableElement method, String name, Operation operation,
                         ResultKind resultKind, boolean distinct,
                         List<? extends VariableElement> parameters,
                         List<ParamBinding> paramBindings,
                         List<Predicate> predicates, List<SortSpec> sorts,
                         PagingMode pagingMode, String customSql) {
        this.method = method;
        this.name = name;
        this.operation = operation;
        this.resultKind = resultKind;
        this.distinct = distinct;
        this.parameters = parameters;
        this.paramBindings = paramBindings;
        this.predicates = predicates;
        this.sorts = sorts;
        this.pagingMode = pagingMode;
        this.customSql = customSql;
        this.navigationDecisions = new LinkedHashMap<>();
        this.typeSnapshot = null;
    }

    public ExecutableElement method() { return method; }
    public String name() { return name; }
    public Operation operation() { return operation; }
    public ResultKind resultKind() { return resultKind; }
    public boolean distinct() { return distinct; }
    public List<? extends VariableElement> parameters() { return parameters; }
    public List<ParamBinding> paramBindings() { return paramBindings; }
    public List<Predicate> predicates() { return predicates; }
    public List<SortSpec> sorts() { return sorts; }
    public PagingMode pagingMode() { return pagingMode; }
    public String customSql() { return customSql; }

    /**
     * Whether the first parameter is the entity itself rather than its identifier.
     *
     * <p>A built-in CRUD method is inherited from {@code BasicRepository<T, K>}, and
     * {@link javax.lang.model.util.Elements#getAllMembers} hands back the member AS DECLARED: its
     * parameter type is the type variable — {@code T} for {@code delete(T)} and
     * {@code deleteById(K)} alike — never the entity the repository substitutes for it. A type
     * variable is therefore resolved by method name; a concrete parameter type is compared to the
     * entity's type name.</p>
     */
    public boolean firstParameterIsEntity(TypeDefinition entity) {
        if (parameters.isEmpty()) {
            return true;
        }
        TypeMirror type = parameters.getFirst().asType();
        if (type.getKind() == TypeKind.TYPEVAR) {
            return !"deleteById".equals(name);
        }
        return type.toString().equals(entity.typeName());
    }

    /**
     * Marks a property path as navigated during query method scanning.
     * Called when analyzing predicates and sort specifications.
     *
     * @param dotPath the dot-separated path (e.g., "order.customer.name")
     * @param mode whether to fetch full entity or just FK
     */
    public void markPropertyNavigated(String dotPath, NavigationMode mode) {
        boolean requiresJoin = dotPath.contains(".");
        boolean backReference = (mode == NavigationMode.FK_ONLY);

        navigationDecisions.put(dotPath, new NavigationDecision(
            dotPath, mode, requiresJoin, backReference
        ));
    }

    /**
     * Returns the navigation decision for a property path, or null if not navigated.
     */
    public NavigationDecision getNavigationDecision(String dotPath) {
        return navigationDecisions.get(dotPath);
    }

    /**
     * Builds and returns the type snapshot for this method context.
     * Should be called after all predicates and sorts have been analyzed.
     */
    public TypeSnapshot buildTypeSnapshot(TypeDefinition rootType) {
        this.typeSnapshot = new TypeSnapshot(rootType, Map.copyOf(navigationDecisions));
        return this.typeSnapshot;
    }

    /**
     * Returns the cached type snapshot, or builds it if not yet created.
     */
    public TypeSnapshot typeSnapshot(TypeDefinition rootType) {
        if (this.typeSnapshot == null) {
            return buildTypeSnapshot(rootType);
        }
        return this.typeSnapshot;
    }

    /**
     * Returns all navigation decisions for this method.
     */
    public Map<String, NavigationDecision> navigationDecisions() {
        return Map.copyOf(navigationDecisions);
    }
}
