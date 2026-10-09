package io.github.fludakit.data.sql;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.EntityDescriptor;
import io.github.fludakit.data.metadata.EntitySchema;
import io.github.fludakit.data.metadata.JoinRef;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryModel;
import io.github.fludakit.data.query.ResolvedPredicate;
import io.github.fludakit.data.query.ResolvedSort;
import io.github.fludakit.data.query.SortSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The engine's ONE SQL renderer. Every statement is a pure function of a schema view
 * ({@link EntitySchema}: the compile-time {@link io.github.fludakit.data.model.DataModel} or a
 * generated {@link io.github.fludakit.data.metadata.EntityDescriptor}) plus, for a whole query method,
 * a resolved {@link io.github.fludakit.data.query.QueryModel}. {@link #build(EntitySchema,
 * io.github.fludakit.data.query.QueryModel)} is the layout hub; the named builders below are its
 * primitives and the classic no-join delegation. Identifier quoting is {@link Names#quote} throughout.
 */

public final class SqlBuilder {

    public String selectBy(EntitySchema md, List<Predicate> predicates, List<SortSpec> sorts, int limit, String cursorClause) {
        String cols = md.columns().stream().map(c -> Names.quote(c.column())).collect(Collectors.joining(", "));
        StringBuilder sb = new StringBuilder("SELECT ").append(cols).append(" FROM ").append(Names.quote(md.tableName()));
        appendWhere(sb, predicates, cursorClause);
        sb.append(orderBy(md, sorts));
        if (limit > 0) sb.append(" LIMIT ").append(limit);
        return sb.toString();
    }

    public String countBy(EntitySchema md, List<Predicate> predicates) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM ").append(Names.quote(md.tableName()));
        appendWhere(sb, predicates, null);
        return sb.toString();
    }

    public String insert(EntitySchema md) {
        return insert(md, true);
    }

    /**
     * Maps a dot-separated property path to the named-parameter token used in the generated SQL
     * (e.g. {@code "address.street"} becomes {@code "address_street"}). Callers that bind parameters
     * programmatically must use the same mapping so the emitted {@code .param(...)} names line up.
     */
    public static String paramName(String path) {
        return path.replace('.', '_');
    }

    /**
     * Builds an INSERT. When {@code includeId} is false the identifier column is omitted so the
     * database can generate it (identity / auto-increment); binding an explicit NULL to an identity
     * column is rejected by most databases, including H2.
     */
    public String insert(EntitySchema md, boolean includeId) {
        List<ColumnRef> cols = md.columns();
        if (!includeId) {
            cols = cols.stream()
                    .filter(c -> md.idColumn() == null || !c.column().equals(md.idColumn()))
                    .collect(Collectors.toList());
        }
        String names = cols.stream().map(c -> Names.quote(c.column())).collect(Collectors.joining(", "));
        String params = cols.stream().map(c -> ":" + paramName(c.path())).collect(Collectors.joining(", "));
        return "INSERT INTO " + Names.quote(md.tableName()) + " (" + names + ") VALUES (" + params + ")";
    }

    public String update(EntitySchema md) {
        List<ColumnRef> cols = md.columns();
        String idCol = Names.quote(md.idColumn());
        String sets = cols.stream()
                .filter(c -> !c.column().equals(md.idColumn()))
                .map(c -> Names.quote(c.column()) + " = :" + paramName(c.path()))
                .collect(Collectors.joining(", "));
        return "UPDATE " + Names.quote(md.tableName()) + " SET " + sets + " WHERE " + idCol + " = :id";
    }

    public String deleteById(EntitySchema md) {
        return "DELETE FROM " + Names.quote(md.tableName()) + " WHERE " + Names.quote(md.idColumn()) + " = :id";
    }

    public String deleteBy(EntitySchema md, List<Predicate> predicates) {
        StringBuilder sb = new StringBuilder("DELETE FROM ").append(Names.quote(md.tableName()));
        appendWhere(sb, predicates, null);
        return sb.toString();
    }

    /** The rendered statement for {@code model} with no extra projection columns. */
    public String build(EntitySchema md, QueryModel<?> model) {
        return build(md, model, "");
    }

    /**
     * The ONE statement layout for a resolved query model (Plan E): predicates and ORDER BY arrive
     * already paired with their physical {@link ColumnRef}s and existential flags, so rendering is a
     * pure function of {@code (schema, model)} plus the emission-time {@code extraProjection}.
     * <ul>
     * <li>An existential (to-many head) predicate ref renders as an {@link #existsConjunct EXISTS}
     *     conjunct and contributes NO join; in DELETE mode every traversed predicate ref does
     *     (a joined {@code DELETE ... LEFT JOIN} is invalid SQL on H2/PostgreSQL/SQL Server).
     *     The DELETE-mode suppression inspects predicate refs only: emitted delete models always
     *     carry empty sorts, so a hand-built model with {@code Operation.DELETE} and joined sort
     *     refs would render an invalid {@code DELETE ... LEFT JOIN} — joined sort refs are not
     *     supported for DELETE.</li>
     * <li>An existential sort renders an {@link #aggregateSortTerm} and contributes NO join; only
     *     to-one predicate refs and to-one sort refs splice the {@link #joinClause} chain.</li>
     * <li>The legacy delegation fires exactly when nothing joins and nothing needs an EXISTS or an
     *     aggregate, so every pre-join constant stays byte-identical through
     *     {@link #countBy}/{@link #deleteBy}/{@link #selectBy}.</li>
     * <li>A {@code model.paging()} other than {@link PagingMode#NONE} renders the BASE constant:
     *     ORDER BY and LIMIT are spliced at runtime by the generated paged read, so the declared
     *     sorts and the static limit are dropped from this rendering.</li>
     * <li>The root-id tiebreak is a runtime cursor concern only; a model's {@code sorts()} render
     *     exactly as declared.</li>
     * </ul>
     * {@code extraProjection} is the joined full-materialisation payload (see
     * {@link #qualifiedRootSelect}) and rides FIND shells only — count and delete ignore it.
     */
    public String build(EntitySchema md, QueryModel<?> model, String extraProjection) {
        Operation operation = model.operation();
        boolean deleteMode = operation == Operation.DELETE;
        boolean countMode = operation == Operation.COUNT || operation == Operation.EXISTS;
        List<ResolvedPredicate> predicates = model.predicates();
        List<ResolvedSort> sorts = model.paging() == PagingMode.NONE
                ? model.sorts() : List.of();
        int limit = model.paging() == PagingMode.NONE ? model.limit() : 0;

        List<ColumnRef> joinRefs = new ArrayList<>();
        List<Integer> existentialIndices = new ArrayList<>();
        List<Predicate> plain = new ArrayList<>(predicates.size());
        List<ColumnRef> predicateRefs = new ArrayList<>(predicates.size());
        for (int i = 0; i < predicates.size(); i++) {
            ResolvedPredicate resolved = predicates.get(i);
            plain.add(resolved.predicate());
            predicateRefs.add(resolved.ref());
            if (resolved.existential() || (deleteMode && !resolved.ref().joins().isEmpty())) {
                existentialIndices.add(i);
            } else {
                joinRefs.add(resolved.ref());
            }
        }
        List<SortSpec> sortSpecs = new ArrayList<>(sorts.size());
        List<ColumnRef> sortRefs = new ArrayList<>(sorts.size());
        List<Boolean> existentialSorts = new ArrayList<>(sorts.size());
        for (ResolvedSort resolved : sorts) {
            sortSpecs.add(resolved.sort());
            sortRefs.add(resolved.ref());
            existentialSorts.add(resolved.existential());
            if (!resolved.existential()) {
                joinRefs.add(resolved.ref());
            }
        }
        if (existentialIndices.isEmpty() && !existentialSorts.contains(Boolean.TRUE)
                && !hasJoins(joinRefs)) {
            if (countMode) {
                return countBy(md, plain);
            }
            if (deleteMode) {
                return deleteBy(md, plain);
            }
            return selectBy(md, plain, sortSpecs, limit, null);
        }
        boolean spliceJoins = hasJoins(joinRefs);
        StringBuilder sb = new StringBuilder();
        if (countMode) {
            sb.append("SELECT COUNT(*) FROM ").append(Names.quote(md.tableName()))
                    .append(joinClause(md, joinRefs));
        } else if (deleteMode) {
            sb.append("DELETE FROM ").append(Names.quote(md.tableName()))
                    .append(joinClause(md, joinRefs));
        } else {
            // Joined find: every root column is qualified by the root table. Unqualified bare names
            // become ambiguous the moment a join adds a table sharing a column name (e.g. the target's
            // own "id"), and a full-materialisation read joins exactly such a table. Qualifying keeps
            // each label = its physical column, so the generated row mapper still reads it by name.
            sb.append(qualifiedRootSelect(md, joinRefs, spliceJoins, extraProjection));
        }
        List<String> conjuncts = new ArrayList<>();
        for (int i = 0; i < plain.size(); i++) {
            if (!existentialIndices.contains(i)) {
                conjuncts.add(render(plain.get(i), predicateRefs.get(i)));
            }
        }
        for (int i : existentialIndices) {
            conjuncts.add(existsConjunct(md, plain.get(i), predicateRefs.get(i)));
        }
        if (!conjuncts.isEmpty()) {
            sb.append(" WHERE ").append(String.join(" AND ", conjuncts));
        }
        if (!sortSpecs.isEmpty()) {
            StringBuilder orderBy = new StringBuilder();
            for (int i = 0; i < sortSpecs.size(); i++) {
                SortSpec sort = sortSpecs.get(i);
                ColumnRef ref = sortRefs.get(i);
                String term = Boolean.TRUE.equals(existentialSorts.get(i))
                        ? aggregateSortTerm(md, ref, sort.ascending())
                        : ref.joins().isEmpty() && ref.table() == null
                                ? Names.quote(ref.column())
                                : qualify(ref, md.tableName());
                if (orderBy.length() > 0) {
                    orderBy.append(", ");
                }
                orderBy.append(term).append(sort.ascending() ? " ASC" : " DESC");
            }
            sb.append(" ORDER BY ").append(orderBy);
        }
        if (limit > 0) {
            sb.append(" LIMIT ").append(limit);
        }
        return sb.toString();
    }

    public String orderBy(EntitySchema md, List<SortSpec> sorts) {
        if (sorts.isEmpty()) return "";
        return " ORDER BY " + sorts.stream()
                .map(s -> Names.quote(resolveColumn(md, s.property())) + (s.ascending() ? " ASC" : " DESC"))
                .collect(Collectors.joining(", "));
    }

    /**
     * Qualifies a ref's leaf column: last-join alias when joins are present, else
     * {@code ref.table()} when non-null, else {@code rootTable}.
     */
    public static String qualify(ColumnRef ref, String rootTable) {
        String qualifier;
        if (!ref.joins().isEmpty()) {
            qualifier = ref.joins().get(ref.joins().size() - 1).alias();
        } else if (ref.table() != null) {
            qualifier = ref.table();
        } else if (rootTable != null) {
            qualifier = rootTable;
        } else {
            throw new IllegalStateException(
                    "Cannot qualify fully-local column ref '" + ref.path() + "': no owning table or root table");
        }
        return Names.quote(qualifier) + "." + Names.quote(ref.column());
    }

    /**
     * The deduplicated {@code LEFT JOIN} chain these refs require. Joins are chained PER PATH:
     * within one ref's joins list, the first join is qualified by the root table and every later
     * join by the previous join's alias IN THAT SAME ref. Across refs, joins are deduplicated by
     * alias (first appearance wins) and emitted in first-appearance order, so a shared head join
     * keeps a single fragment while each ref's divergent tail chains off its own predecessor.
     * Each fragment is
     * {@code  LEFT JOIN "<table>" AS "<alias>" ON <qualifier>."<parentColumn>" = "<alias>"."<childColumn>";}
     * Returns {@code ""} when no ref carries a join.
     */
    public static String joinClause(EntitySchema md, List<ColumnRef> refs) {
        Map<String, JoinRef> byAlias = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        for (ColumnRef ref : refs) {
            // Task 6 (R-T6): a sort/paging ref whose head is to-many renders as an aggregate
            // subquery (see aggregateSortTerm), NEVER a fanning LEFT JOIN. The schema answers —
            // the compile-time DataModel from its Prop shapes, a generated descriptor from its
            // attribute kinds — so this renderer holds no metamodel import.
            String head = relationHead(ref);
            if (head != null && md.isToManyHead(head)) {
                continue;
            }
            String previous = null;
            for (JoinRef join : ref.joins()) {
                if (byAlias.containsKey(join.alias())) {
                    previous = join.alias();
                    continue;
                }
                byAlias.put(join.alias(), join);
                String qualifier = previous == null ? Names.quote(md.tableName()) : Names.quote(previous);
                previous = join.alias();
                String alias = Names.quote(join.alias());
                sb.append(" LEFT JOIN ").append(Names.quote(join.table()))
                        .append(" AS ").append(alias)
                        .append(" ON ").append(qualifier).append('.').append(Names.quote(join.parentColumn()))
                        .append(" = ").append(alias).append('.').append(Names.quote(join.childColumn()));
            }
        }
        return sb.toString();
    }

    /**
     * Resolves a sort property — a bare attribute name or an embedded dot path such as
     * {@code "address.city"} — to its physical column via the descriptor's flattened view.
     */
    public static String resolveColumn(EntitySchema md, String property) {
        for (ColumnRef c : md.columns()) {
            if (c.path().equals(property)) return c.column();
        }
        throw new IllegalArgumentException("Unknown sort property: " + property);
    }

    private void appendWhere(StringBuilder sb, List<Predicate> predicates, String cursorClause) {
        StringBuilder where = new StringBuilder();
        if (!predicates.isEmpty()) {
            for (Predicate p : predicates) {
                if (where.length() > 0) where.append(" AND ");
                where.append(render(p));
            }
        }
        if (cursorClause != null && !cursorClause.isBlank()) {
            if (where.length() > 0) where.append(" AND ");
            where.append(cursorClause);
        }
        if (where.length() > 0) sb.append(" WHERE ").append(where);
    }

    private String render(Predicate p) {
        return renderOperator(p.operator(), Names.quote(Names.columnName(p.property())), p.param());
    }

    /**
     * Renders a predicate with its column qualified by {@code ref}: last-join alias, then
     * explicit table, and fully-local refs stay unqualified for legacy parity.
     */
    public static String render(Predicate p, ColumnRef ref) {
        String col = ref.joins().isEmpty() && ref.table() == null
                ? Names.quote(ref.column())
                : qualify(ref, null);
        return renderOperator(p.operator(), col, p.param());
    }

    /**
     * The EXISTS conjunct a predicate on an existential (to-many head) — or on ANY traversed ref in a
     * DELETE — renders instead of a joined WHERE term: the ref's LAST join edge supplies the child
     * table, the relation-property alias and the alias-side column; the correlation closes on the
     * edge's root-side column ({@code parentColumn}) — the root id for a to-many head, the owner-side
     * FK for a to-one head. The leaf term is {@link #render(Predicate, ColumnRef)}'s alias-qualified
     * rendering, so the named parameter and the caller's binding are unchanged.
     */
    public static String existsConjunct(EntitySchema md, Predicate p, ColumnRef ref) {
        JoinRef join = ref.joins().get(ref.joins().size() - 1);
        return "EXISTS (SELECT 1 FROM " + Names.quote(join.table()) + " " + Names.quote(join.alias())
                + " WHERE " + Names.quote(join.alias()) + "." + Names.quote(join.childColumn())
                + " = " + Names.quote(md.tableName()) + "." + Names.quote(join.parentColumn())
                + " AND " + render(p, ref) + ")";
    }

    /** The first dot segment of a joined ref's path, or {@code null} when the ref joins nothing. */
    private static String relationHead(ColumnRef ref) {
        if (ref.joins().isEmpty()) {
            return null;
        }
        int dot = ref.path().indexOf('.');
        return dot < 0 ? null : ref.path().substring(0, dot);
    }

    /** True when any resolved ref carries a join, i.e. the statement must splice a LEFT JOIN chain. */
    private static boolean hasJoins(List<ColumnRef> refs) {
        for (ColumnRef r : refs) {
            if (!r.joins().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code ref}'s relation head — the FIRST dot segment of its path, joins required — is a
     * to-many head of the descriptor. Kept as the descriptor-typed entry point the generated paged
     * read calls; the decision itself lives in {@link EntitySchema#isToManyHead(String)}. A ref that
     * joins nothing, a head the descriptor does not know: {@code false}.
     */
    public static boolean isToManyHead(EntityDescriptor<?> md, ColumnRef ref) {
        String head = relationHead(ref);
        return head != null && md.isToManyHead(head);
    }

    /**
     * The correlated scalar subquery a to-many sort orders by: {@code MIN} of the leaf column for an
     * ascending key, {@code MAX} for a descending one — exactly the edge the descriptor-driven cursor
     * key reduction (Task 4) collapses the value list to, so seek and sort agree. The head join opens
     * the subquery {@code FROM} (alias WITHOUT {@code AS}, mirroring nothing else on the statement);
     * deeper edges chain inside as the same {@code LEFT JOIN ... AS ... ON ...} fragments
     * {@link #joinClause} emits, qualified per-path. Correlation is the head's
     * {@code childFk = rootTable.rootId} pair taken from its {@link JoinRef}. Every identifier goes
     * through {@link Names#quote}; the result is used VERBATIM as an ORDER BY / seek term.
     */
    public static String aggregateSortTerm(EntitySchema md, ColumnRef ref, boolean ascending) {
        if (ref.joins().isEmpty()) {
            throw new IllegalArgumentException(
                    "aggregate sort term requires a relation path: " + ref.path());
        }
        JoinRef head = ref.joins().get(0);
        StringBuilder sb = new StringBuilder("(SELECT ")
                .append(ascending ? "MIN(" : "MAX(")
                .append(qualify(ref, md.tableName()))
                .append(") FROM ").append(Names.quote(head.table()))
                .append(' ').append(Names.quote(head.alias()));
        String previous = head.alias();
        for (int i = 1; i < ref.joins().size(); i++) {
            JoinRef join = ref.joins().get(i);
            sb.append(" LEFT JOIN ").append(Names.quote(join.table()))
                    .append(" AS ").append(Names.quote(join.alias()))
                    .append(" ON ").append(Names.quote(previous)).append('.').append(Names.quote(join.parentColumn()))
                    .append(" = ").append(Names.quote(join.alias())).append('.').append(Names.quote(join.childColumn()));
            previous = join.alias();
        }
        sb.append(" WHERE ").append(Names.quote(head.alias())).append('.').append(Names.quote(head.childColumn()))
                .append(" = ").append(Names.quote(md.tableName())).append('.').append(Names.quote(head.parentColumn()))
                .append(')');
        return sb.toString();
    }

    /**
     * The ONE rendering of the root-table projection/join shell (S-1): a SELECT list of the root
     * columns (comma-separated in schema order, optionally followed by a comma and
     * {@code extraProjection}), FROM the quoted table, then the deduped {@link #joinClause} chain for
     * {@code traversedToOne}. {@code qualified} keeps the deliberate split intact: a statement that
     * splices joins qualifies every leaf ({@code "root"."col"}, so a joined table sharing a column name
     * cannot make the list ambiguous) while an EXISTS-only statement keeps the bare {@code "col"}
     * projection. Empty join refs render no chain, which is how the paged findAll constant stays
     * compatible with the runtime splicing its own joins string. A bare projection carrying a non-empty
     * {@code extraProjection} is unreachable through emission (full materialisation implies a to-one
     * join, which implies qualified), so it is a precondition throw, not new logic.
     */
    public static String qualifiedRootSelect(EntitySchema md, List<ColumnRef> traversedToOne,
                                             boolean qualified, String extraProjection) {
        if (!qualified && extraProjection != null && !extraProjection.isEmpty()) {
            throw new IllegalStateException("bare projection cannot carry extraProjection");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ").append(md.columns().stream()
                .map(c -> qualified ? qualifiedColumn(md.tableName(), c.column()) : Names.quote(c.column()))
                .collect(Collectors.joining(", ")));
        if (extraProjection != null && !extraProjection.isEmpty()) {
            sb.append(", ").append(extraProjection);
        }
        sb.append(" FROM ").append(Names.quote(md.tableName()));
        sb.append(joinClause(md, traversedToOne));
        return sb.toString();
    }

    /** The single {@code "qualifier"."column"} rendering shared by every qualified projection (S-1). */
    private static String qualifiedColumn(String qualifier, String column) {
        return Names.quote(qualifier) + "." + Names.quote(column);
    }

    private static String renderOperator(Operator op, String col, String param) {
        return switch (op) {
            case EQ -> col + " = :" + param;
            case NOT_EQ -> col + " <> :" + param;
            case LESS_THAN -> col + " < :" + param;
            case LESS_THAN_EQUAL -> col + " <= :" + param;
            case GREATER_THAN -> col + " > :" + param;
            case GREATER_THAN_EQUAL -> col + " >= :" + param;
            case LIKE, CONTAINS, STARTS_WITH, ENDS_WITH -> col + " LIKE :" + param;
            case NOT_LIKE -> col + " NOT LIKE :" + param;
            case BETWEEN -> col + " BETWEEN :" + param + "_0 AND :" + param + "_1";
            case IN -> col + " IN (:" + param + ")";
            case NOT_IN -> col + " NOT IN (:" + param + ")";
            case IS_NULL -> col + " IS NULL";
            case IS_NOT_NULL -> col + " IS NOT NULL";
            case TRUE -> col + " = TRUE";
            case FALSE -> col + " = FALSE";
            case IGNORE_CASE_EQ -> "LOWER(" + col + ") = LOWER(:" + param + ")";
        };
    }
}
