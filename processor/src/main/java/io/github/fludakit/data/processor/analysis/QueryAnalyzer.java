package io.github.fludakit.data.processor.analysis;

import io.github.fludakit.data.metadata.*;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link NavigationPlan} from a {@link MethodContext} and {@link TypeDefinition}.
 *
 * <p>Analyzes the method's predicates and sorts to determine which relationships are navigated,
 * computes table aliases, and assigns load strategies based on the owner type and navigation context.</p>
 */
public final class QueryAnalyzer {

    private QueryAnalyzer() {}

    /**
     * Analyzes the method and builds a navigation plan.
     *
     * @param method the method context (predicates, sorts, paging)
     * @param rootType the root entity's type definition
     * @return the navigation plan
     */
    public static NavigationPlan analyze(MethodContext method, TypeDefinition rootType) {
        String rootTable = rootType.tableName();
        TableAlias aliasGen = new TableAlias();
        String rootAlias = aliasGen.root(rootTable);

        List<NavigationPlan.JoinSpec> joinSpecs = new ArrayList<>();
        int maxDepth = 0;

        // Analyze predicates
        for (Predicate pred : method.predicates()) {
            String path = pred.property();
            if (path.contains(".")) {
                ColumnPlacement placement = rootType.resolve(path);
                if (!placement.local()) {
                    int depth = addJoinSpecs(placement, joinSpecs, aliasGen, rootType, path);
                    maxDepth = Math.max(maxDepth, depth);
                }
            }
        }

        // Analyze sorts
        for (SortSpec sort : method.sorts()) {
            String path = sort.property();
            if (path.contains(".")) {
                ColumnPlacement placement = rootType.resolve(path);
                if (!placement.local()) {
                    int depth = addJoinSpecs(placement, joinSpecs, aliasGen, rootType, path);
                    maxDepth = Math.max(maxDepth, depth);
                }
            }
        }

        return new NavigationPlan(rootTable, rootAlias, List.copyOf(joinSpecs), maxDepth);
    }

    private static int addJoinSpecs(ColumnPlacement placement, List<NavigationPlan.JoinSpec> specs,
                                    TableAlias aliasGen, TypeDefinition rootType, String fullPath) {
        int depth = 0;
        for (JoinEdge edge : placement.edges()) {
            depth++;
            String alias = aliasGen.next(edge.childTable());
            // Determine load strategy: if this edge is part of a sort/predicate path, it's EAGER
            LoadStrategy strategy = LoadStrategy.EAGER;
            specs.add(new NavigationPlan.JoinSpec(edge, alias, strategy, fullPath, depth));
        }
        return depth;
    }
}
