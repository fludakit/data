package io.github.fludakit.data.metadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The immutable result of analyzing a query method's navigation requirements.
 *
 * <p>Built by {@code QueryAnalyzer} (processor) from a {@code MethodContext} and {@code TypeDefinition}.
 * Consumed by {@code SqlFragment}, {@code QueryCompiler} (core) for alias-aware SQL rendering, and by
 * emitters (processor) for load-strategy-aware code generation.</p>
 *
 * <p>Carries everything the SQL layer needs to render joins with unique aliases, and everything the
 * emitter layer needs to decide what to fetch, materialize, and hydrate.</p>
 *
 * @param rootTable      the root entity's table name
 * @param rootAlias      the root table's alias (e.g. "orders_0")
 * @param joinSpecs      the join edges in traversal order, each with its alias and load strategy
 * @param maxDepth       the deepest navigation depth (0 = root only, 1 = one hop, etc.)
 */
public record NavigationPlan(
    String rootTable,
    String rootAlias,
    List<JoinSpec> joinSpecs,
    int maxDepth
) {
    public NavigationPlan {
        Objects.requireNonNull(rootTable, "rootTable");
        Objects.requireNonNull(rootAlias, "rootAlias");
        joinSpecs = joinSpecs == null ? List.of() : List.copyOf(joinSpecs);
        if (maxDepth < 0) throw new IllegalArgumentException("maxDepth must be >= 0");
    }

    /**
     * One join edge with its alias and load strategy.
     *
     * @param edge         the join edge (ToOne or ToMany)
     * @param alias        the unique table alias (e.g. "customers_1")
     * @param strategy     how this relationship is loaded
     * @param propertyPath the dot-path from root (e.g. "customer" or "items.product")
     * @param depth        navigation depth (1 for direct association, 2 for association.association, etc.)
     */
    public record JoinSpec(
        JoinEdge edge,
        String alias,
        LoadStrategy strategy,
        String propertyPath,
        int depth
    ) {
        public JoinSpec {
            Objects.requireNonNull(edge, "edge");
            Objects.requireNonNull(alias, "alias");
            Objects.requireNonNull(strategy, "strategy");
            Objects.requireNonNull(propertyPath, "propertyPath");
        }
    }

    /** Returns the alias for a child table, or null if not joined. */
    public String aliasFor(String childTable) {
        for (JoinSpec spec : joinSpecs) {
            if (spec.edge().childTable().equals(childTable)) {
                return spec.alias();
            }
        }
        return null;
    }

    /** Returns the JoinSpec for a property path, or null if not navigated. */
    public JoinSpec forPath(String propertyPath) {
        for (JoinSpec spec : joinSpecs) {
            if (spec.propertyPath().equals(propertyPath)) {
                return spec;
            }
        }
        return null;
    }

    /** Returns all JoinSpecs with the given load strategy. */
    public List<JoinSpec> specsFor(LoadStrategy strategy) {
        return joinSpecs.stream()
            .filter(s -> s.strategy() == strategy)
            .toList();
    }

    /** Whether this plan has any joins. */
    public boolean hasJoins() {
        return !joinSpecs.isEmpty();
    }

    /** Builds an alias map: childTable -> alias. */
    public Map<String, String> aliasMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(rootTable, rootAlias);
        for (JoinSpec spec : joinSpecs) {
            map.putIfAbsent(spec.edge().childTable(), spec.alias());
        }
        return Map.copyOf(map);
    }
}
