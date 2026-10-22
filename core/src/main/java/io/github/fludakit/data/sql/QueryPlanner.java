package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.ColumnResolver;
import io.github.fludakit.data.metadata.JoinEdge;
import io.github.fludakit.data.metadata.NavigationPlan;
import io.github.fludakit.data.metadata.TableSchema;
import io.github.fludakit.data.query.Connector;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.SortSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * One pass over a logical query model: resolves and classifies every predicate and sort ref into a
 * {@link QueryCompiler.Plan}. Split from {@link QueryCompiler} so the planning concern has a class of
 * its own; the rendering primitives it composes ({@code resolveColumn}, {@code existsConjunct},
 * {@code aggregateSortTerm}, ...) stay on the compiler, which owns the SQL text.
 */
final class QueryPlanner {

    private final TableSchema schema;
    private final ColumnResolver attributes;
    private final SqlDialect dialect;
    private final boolean deleteMode;
    private final List<JoinEdge> extraEdges;
    private final List<Predicate> predicates;
    private final List<SortSpec> sorts;
    private final int limit;
    private final NavigationPlan navPlan;

    private final List<JoinEdge> joinEdges = new ArrayList<>();
    private final List<Integer> existentialIndices = new ArrayList<>();
    private final List<ColumnPlacement> predicateRefs = new ArrayList<>();
    private final List<ColumnPlacement> sortRefs = new ArrayList<>();
    private final List<Boolean> existentialSorts = new ArrayList<>();

    QueryPlanner(TableSchema schema, ColumnResolver attributes, SqlDialect dialect,
                 QueryDefinition.Criteria model, List<JoinEdge> extraEdges) {
        this(schema, attributes, dialect, model, extraEdges, null);
    }

    QueryPlanner(TableSchema schema, ColumnResolver attributes, SqlDialect dialect,
                 QueryDefinition.Criteria model, List<JoinEdge> extraEdges, NavigationPlan navPlan) {
        this.schema = schema;
        this.attributes = attributes;
        this.dialect = dialect;
        this.extraEdges = List.copyOf(extraEdges);
        this.deleteMode = model instanceof QueryDefinition.DeleteWhere;
        this.predicates = model.predicates();
        // Only a read orders and caps its rows: a count, an exists and a delete have neither, and
        // a paged read renders the base constant and leaves the window to the runtime.
        QueryDefinition.Read read = model instanceof QueryDefinition.Read r ? r : null;
        boolean windowless = read == null || read.paging() != PagingMode.NONE;
        this.sorts = windowless ? List.of() : read.sorts();
        this.limit = windowless ? 0 : read.limit().limit();
        this.navPlan = navPlan;
    }

    QueryCompiler.Plan plan() {
        joinEdges.addAll(extraEdges);
        for (int i = 0; i < predicates.size(); i++) {
            Predicate predicate = predicates.get(i);
            ColumnPlacement ref = QueryCompiler.resolveColumn(schema, attributes, predicate.property());
            predicateRefs.add(ref);
            if (QueryCompiler.isExistential(attributes, predicate.property()) || (deleteMode && !ref.local())) {
                existentialIndices.add(i);
            } else {
                joinEdges.addAll(ref.edges());
            }
        }
        for (SortSpec sort : sorts) {
            ColumnPlacement ref = QueryCompiler.resolveColumn(schema, attributes, sort.property());
            boolean existential = QueryCompiler.isExistential(attributes, sort.property());
            sortRefs.add(ref);
            existentialSorts.add(existential);
            if (!existential) {
                joinEdges.addAll(ref.edges());
            }
        }
        return new QueryCompiler.Plan(List.copyOf(joinEdges), where(), orderBy(), limit);
    }

    private List<QueryCompiler.Conjunct> where() {
        List<QueryCompiler.Conjunct> conjuncts = new ArrayList<>();
        boolean spliceJoins = !joinEdges.isEmpty();
        if (predicates.stream().anyMatch(p -> Connector.isOr(p.connector()))) {
            // With any OR the declaration order is load-bearing, so each predicate renders in
            // place — and its shape decides the renderer. The all-AND branch below may reorder
            // freely.
            for (int i = 0; i < predicates.size(); i++) {
                conjuncts.add(new QueryCompiler.Conjunct(conjunctAt(i, spliceJoins),
                        i > 0 && Connector.isOr(predicates.get(i).connector())));
            }
        } else {
            for (int i = 0; i < predicates.size(); i++) {
                if (!existentialIndices.contains(i)) {
                    conjuncts.add(new QueryCompiler.Conjunct(conjunctAt(i, spliceJoins), false));
                }
            }
            for (int i : existentialIndices) {
                conjuncts.add(new QueryCompiler.Conjunct(conjunctAt(i, spliceJoins), false));
            }
        }
        return conjuncts;
    }

    private String conjunctAt(int index, boolean spliceJoins) {
        Predicate predicate = predicates.get(index);
        ColumnPlacement ref = predicateRefs.get(index);
        return existentialIndices.contains(index)
                ? QueryCompiler.existsConjunct(dialect, predicate, ref)
                : QueryCompiler.render(dialect, predicate, ref, schema.tableName(), spliceJoins);
    }

    private List<String> orderBy() {
        List<String> terms = new ArrayList<>(sorts.size());
        for (int i = 0; i < sorts.size(); i++) {
            SortSpec sort = sorts.get(i);
            ColumnPlacement ref = sortRefs.get(i);
            boolean existential = Boolean.TRUE.equals(existentialSorts.get(i));
            String term = existential
                    ? QueryCompiler.aggregateSortTerm(dialect, schema, ref, sort.ascending())
                    : QueryCompiler.isLocalTo(ref.leaf(), schema.tableName())
                            ? dialect.quoteIdentifier(ref.leaf().column())
                            : QueryCompiler.qualify(dialect, ref.leaf(), schema.tableName());
            terms.add(QueryCompiler.orderTerm(term, sort, existential));
        }
        return terms;
    }
}
