package io.github.fludakit.data.sql;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.JoinEdge;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.metadata.UnknownAttributePathException;
import io.github.fludakit.data.query.Connector;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Order;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.SortSpec;
import io.github.fludakit.data.query.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Compiles a logical {@link QueryDefinition} into an {@link SqlFragment}. Path resolution, join
 * versus {@code EXISTS} classification, projection, and the window policy all live here — there is
 * no separate planner type.
 *
 * <p>{@link SqlBuilder} turns a fragment into a literal (and optionally formats it). This class
 * never returns a formatted string.</p>
 */
public final class QueryCompiler {

    private QueryCompiler() {
    }

    // ------------------------------------------------------------------- entry points

    /** Compiles {@code query} against {@code typeModel}. */
    public static SqlFragment compile(QueryDefinition query, TypeDefinition typeModel, SqlDialect dialect) {
        return compile(query, typeModel, dialect, List.of(), null);
    }

    /**
     * As {@link #compile(QueryDefinition, TypeDefinition, SqlDialect)}, seeding joins with
     * {@code extraEdges} and appending {@code extraProjection} on a find shell.
     */
    public static SqlFragment compile(QueryDefinition query, TypeDefinition typeModel, SqlDialect dialect,
                                      List<JoinEdge> extraEdges, String extraProjection) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(typeModel, "typeModel");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(extraEdges, "extraEdges");
        return switch (query) {
            case QueryDefinition.Insert insert -> insert(dialect, typeModel, insert.includeId());
            case QueryDefinition.Upsert ignored -> upsert(dialect, typeModel);
            case QueryDefinition.Update ignored -> update(dialect, typeModel);
            case QueryDefinition.DeleteById ignored -> deleteById(dialect, typeModel);
            case QueryDefinition.Native nativeQuery -> new SqlFragment.Opaque(nativeQuery.sql());
            case QueryDefinition.Criteria criteria -> compileCriteria(criteria, typeModel, dialect,
                    extraEdges, extraProjection);
        };
    }

    // ------------------------------------------------------------------- criteria compilation

    private static SqlFragment compileCriteria(QueryDefinition.Criteria model, TypeDefinition typeModel,
                                               SqlDialect dialect, List<JoinEdge> extraEdges,
                                               String extraProjection) {
        boolean deleteMode = model instanceof QueryDefinition.DeleteWhere;
        boolean countMode = model instanceof QueryDefinition.Count || model instanceof QueryDefinition.Exists;
        List<Predicate> predicates = model.predicates();
        List<SortSpec> sorts = List.of();
        int limit = 0;
        if (model instanceof QueryDefinition.Read read) {
            boolean bakeWindow = read.window() instanceof Window.None;
            boolean bakeOrder = bakeWindow && read.order() instanceof Order.Static;
            sorts = bakeOrder ? read.sorts() : List.of();
            limit = bakeWindow ? ((Window.None) read.window()).limit().limit() : 0;
        }

        List<JoinEdge> joinEdges = new ArrayList<>(extraEdges);
        List<Integer> existentialIndices = new ArrayList<>();
        List<ColumnPlacement> predicateRefs = new ArrayList<>();
        List<ColumnPlacement> sortRefs = new ArrayList<>();
        List<Boolean> existentialSorts = new ArrayList<>();

        for (int i = 0; i < predicates.size(); i++) {
            Predicate predicate = predicates.get(i);
            ColumnPlacement ref = resolveColumn(typeModel, predicate.property());
            predicateRefs.add(ref);
            if (isExistential(typeModel, predicate.property()) || (deleteMode && !ref.local())) {
                existentialIndices.add(i);
            } else {
                joinEdges.addAll(ref.edges());
            }
        }
        for (SortSpec sort : sorts) {
            ColumnPlacement ref = resolveColumn(typeModel, sort.property());
            boolean existential = isExistential(typeModel, sort.property());
            sortRefs.add(ref);
            existentialSorts.add(existential);
            if (!existential) {
                joinEdges.addAll(ref.edges());
            }
        }

        boolean spliceJoins = !joinEdges.isEmpty();
        List<SqlFragment.Conjunct> where = whereConjuncts(dialect, typeModel, predicates, predicateRefs,
                existentialIndices, spliceJoins);
        List<String> orderBy = orderByTerms(dialect, typeModel, sorts, sortRefs, existentialSorts, spliceJoins);

        SqlFragment.Query.Builder fragment = SqlFragment.Query.builder()
                .table(typeModel.tableName())
                .join(joinEdges);
        if (countMode) {
            boolean distinct = model instanceof QueryDefinition.Count c && c.distinct();
            fragment.keyword("SELECT").projection(distinct && typeModel.idColumn() != null
                    ? "COUNT(DISTINCT " + dialect.quoteIdentifier(typeModel.idColumn()) + ")"
                    : "COUNT(*)");
        } else if (deleteMode) {
            fragment.keyword("DELETE");
        } else {
            boolean distinct = model instanceof QueryDefinition.Read r && r.distinct();
            fragment.keyword("SELECT")
                    .projection(rootProjection(dialect, typeModel, spliceJoins, extraProjection))
                    .distinct(distinct);
        }
        for (SqlFragment.Conjunct conjunct : where) {
            fragment.where(conjunct);
        }
        for (String term : orderBy) {
            fragment.orderBy(term);
        }
        if (limit > 0) {
            fragment.limit(limit);
        }
        return fragment.build();
    }

    private static List<SqlFragment.Conjunct> whereConjuncts(
            SqlDialect dialect, TypeDefinition typeModel, List<Predicate> predicates,
            List<ColumnPlacement> predicateRefs, List<Integer> existentialIndices, boolean spliceJoins) {
        List<SqlFragment.Conjunct> conjuncts = new ArrayList<>();
        if (predicates.stream().anyMatch(p -> Connector.isOr(p.connector()))) {
            for (int i = 0; i < predicates.size(); i++) {
                conjuncts.add(new SqlFragment.Conjunct(
                        conjunctAt(dialect, typeModel, predicates, predicateRefs, existentialIndices, i, spliceJoins),
                        i > 0 && Connector.isOr(predicates.get(i).connector())));
            }
        } else {
            for (int i = 0; i < predicates.size(); i++) {
                if (!existentialIndices.contains(i)) {
                    conjuncts.add(new SqlFragment.Conjunct(
                            conjunctAt(dialect, typeModel, predicates, predicateRefs, existentialIndices, i, spliceJoins),
                            false));
                }
            }
            for (int i : existentialIndices) {
                conjuncts.add(new SqlFragment.Conjunct(
                        conjunctAt(dialect, typeModel, predicates, predicateRefs, existentialIndices, i, spliceJoins),
                        false));
            }
        }
        return conjuncts;
    }

    private static String conjunctAt(SqlDialect dialect, TypeDefinition typeModel, List<Predicate> predicates,
                                     List<ColumnPlacement> predicateRefs, List<Integer> existentialIndices,
                                     int index, boolean spliceJoins) {
        Predicate predicate = predicates.get(index);
        ColumnPlacement ref = predicateRefs.get(index);
        return existentialIndices.contains(index)
                ? existsConjunct(dialect, predicate, ref)
                : renderPredicate(dialect, predicate, ref, typeModel.tableName(), spliceJoins);
    }

    private static List<String> orderByTerms(SqlDialect dialect, TypeDefinition typeModel, List<SortSpec> sorts,
                                             List<ColumnPlacement> sortRefs, List<Boolean> existentialSorts,
                                             boolean spliceJoins) {
        List<String> terms = new ArrayList<>(sorts.size());
        for (int i = 0; i < sorts.size(); i++) {
            SortSpec sort = sorts.get(i);
            ColumnPlacement ref = sortRefs.get(i);
            boolean existential = Boolean.TRUE.equals(existentialSorts.get(i));
            String term = existential
                    ? aggregateSortTerm(dialect, typeModel, ref, sort.ascending())
                    : isLocalTo(ref.leaf(), typeModel.tableName()) && !spliceJoins
                            ? dialect.quoteIdentifier(ref.leaf().column())
                            : qualify(dialect, ref.leaf(), typeModel.tableName());
            terms.add(orderTerm(term, sort, existential));
        }
        return terms;
    }

    private static String rootProjection(SqlDialect dialect, TypeDefinition typeModel, boolean qualified,
                                         String extraProjection) {
        if (!qualified && extraProjection != null && !extraProjection.isEmpty()) {
            throw new IllegalStateException("bare projection cannot carry extraProjection");
        }
        String columns = typeModel.columns().stream()
                .map(c -> qualified ? SqlFragment.qualified(dialect, typeModel.tableName(), c.column())
                        : dialect.quoteIdentifier(c.column()))
                .collect(Collectors.joining(", "));
        return extraProjection == null || extraProjection.isEmpty()
                ? columns
                : columns + ", " + extraProjection;
    }

    // ------------------------------------------------------------------- lifecycle / physical → Opaque

    /** Physical cascade DELETE by parent FK. */
    public static SqlFragment deleteByFk(SqlDialect dialect, String table, String fkColumn) {
        return new SqlFragment.Opaque("DELETE FROM " + dialect.quoteIdentifier(table)
                + " WHERE " + dialect.quoteIdentifier(fkColumn) + " = :parentId");
    }

    /** Physical select of a foreign-key column by identifier. */
    public static SqlFragment selectForeignKey(SqlDialect dialect, TypeDefinition typeModel, String fkColumn) {
        return new SqlFragment.Opaque("SELECT " + dialect.quoteIdentifier(fkColumn)
                + " FROM " + dialect.quoteIdentifier(typeModel.tableName())
                + " WHERE " + dialect.quoteIdentifier(typeModel.idColumn()) + " = :id");
    }

    /** Physical orphan DELETE by child id and parent FK. */
    public static SqlFragment deleteChildByIdAndFk(SqlDialect dialect, String table, String idColumn, String fkColumn) {
        return new SqlFragment.Opaque("DELETE FROM " + dialect.quoteIdentifier(table)
                + " WHERE " + dialect.quoteIdentifier(idColumn) + " = :childId"
                + " AND " + dialect.quoteIdentifier(fkColumn) + " = :parentId");
    }

    /** Physical select of child ids by parent FK. */
    public static SqlFragment selectChildIds(SqlDialect dialect, String table, String idColumn, String fkColumn) {
        return new SqlFragment.Opaque("SELECT " + dialect.quoteIdentifier(idColumn)
                + " FROM " + dialect.quoteIdentifier(table)
                + " WHERE " + dialect.quoteIdentifier(fkColumn) + " = :parentId");
    }

    /** Physical INSERT with an explicit column/parameter list. */
    public static SqlFragment insertColumns(SqlDialect dialect, String table, List<String> columns,
                                            List<String> paramNames) {
        String names = columns.stream().map(dialect::quoteIdentifier).collect(Collectors.joining(", "));
        String params = paramNames.stream().map(n -> ":" + paramName(n)).collect(Collectors.joining(", "));
        return new SqlFragment.Opaque("INSERT INTO " + dialect.quoteIdentifier(table)
                + " (" + names + ") VALUES (" + params + ")");
    }

    /** Correlated aggregate sort term for a non-hydrated to-many cursor sort. */
    public static SqlFragment correlatedSort(SqlDialect dialect, String childTable, String leafColumn,
                                             String fkColumn, String ownerTable, String ownerIdColumn,
                                             boolean ascending) {
        return new SqlFragment.Opaque("(SELECT " + (ascending ? "MIN" : "MAX")
                + "(" + dialect.quoteIdentifier(childTable) + "." + dialect.quoteIdentifier(leafColumn) + ")"
                + " FROM " + dialect.quoteIdentifier(childTable)
                + " WHERE " + dialect.quoteIdentifier(childTable) + "." + dialect.quoteIdentifier(fkColumn)
                + " = " + dialect.quoteIdentifier(ownerTable) + "." + dialect.quoteIdentifier(ownerIdColumn) + ")");
    }

    /** Child-table hydration SELECT. */
    public static SqlFragment selectByFk(SqlDialect dialect, String table, String whereColumn,
                                         String paramName, String projection) {
        String proj = projection == null || projection.isEmpty() ? "*" : projection;
        return new SqlFragment.Opaque("SELECT " + proj + " FROM " + dialect.quoteIdentifier(table)
                + " WHERE " + dialect.quoteIdentifier(whereColumn) + " = :" + paramName);
    }

    public static SqlFragment insert(SqlDialect dialect, TypeDefinition typeModel, boolean includeId) {
        List<ColumnRef> cols = typeModel.columns();
        if (!includeId) {
            cols = cols.stream()
                    .filter(c -> typeModel.idColumn() == null || !c.column().equals(typeModel.idColumn()))
                    .toList();
        }
        if (cols.isEmpty()) {
            return new SqlFragment.Opaque(dialect.insertDefaultValues(typeModel.tableName()));
        }
        String names = cols.stream().map(c -> dialect.quoteIdentifier(c.column())).collect(Collectors.joining(", "));
        String params = cols.stream().map(c -> ":" + paramName(attributePath(typeModel, c)))
                .collect(Collectors.joining(", "));
        return new SqlFragment.Opaque("INSERT INTO " + dialect.quoteIdentifier(typeModel.tableName())
                + " (" + names + ") VALUES (" + params + ")");
    }

    public static SqlFragment upsert(SqlDialect dialect, TypeDefinition typeModel) {
        List<ColumnRef> cols = typeModel.columns();
        List<String> names = cols.stream().map(ColumnRef::column).toList();
        List<String> params = cols.stream().map(c -> ":" + paramName(attributePath(typeModel, c))).toList();
        return new SqlFragment.Opaque(dialect.upsertStatement(typeModel.tableName(), typeModel.idColumn(), names, params));
    }

    public static SqlFragment update(SqlDialect dialect, TypeDefinition typeModel) {
        String idCol = dialect.quoteIdentifier(typeModel.idColumn());
        String sets = typeModel.columns().stream()
                .filter(c -> !c.column().equals(typeModel.idColumn()))
                .map(c -> dialect.quoteIdentifier(c.column()) + " = :" + paramName(attributePath(typeModel, c)))
                .collect(Collectors.joining(", "));
        if (sets.isEmpty()) {
            return new SqlFragment.Opaque("UPDATE " + dialect.quoteIdentifier(typeModel.tableName())
                    + " SET " + idCol + " = " + idCol + " WHERE " + idCol + " = :id");
        }
        return new SqlFragment.Opaque("UPDATE " + dialect.quoteIdentifier(typeModel.tableName())
                + " SET " + sets + " WHERE " + idCol + " = :id");
    }

    public static SqlFragment deleteById(SqlDialect dialect, TypeDefinition typeModel) {
        return new SqlFragment.Opaque("DELETE FROM " + dialect.quoteIdentifier(typeModel.tableName())
                + " WHERE " + dialect.quoteIdentifier(typeModel.idColumn()) + " = :id");
    }

    public static String rowMapperProjection(SqlDialect dialect, TypeDefinition type,
                                             boolean associationAliasIsName) {
        List<String> cols = new ArrayList<>();
        ProjectionColumnCollector collector = new ProjectionColumnCollector(cols, dialect, associationAliasIsName);
        for (TypeDefinition.Prop prop : type.properties()) {
            prop.accept(collector);
        }
        return String.join(", ", cols);
    }

    private static final class ProjectionColumnCollector implements TypeDefinition.Prop.Visitor<Void> {
        private final List<String> cols;
        private final SqlDialect dialect;
        private final boolean associationAliasIsName;

        ProjectionColumnCollector(List<String> cols, SqlDialect dialect, boolean associationAliasIsName) {
            this.cols = cols;
            this.dialect = dialect;
            this.associationAliasIsName = associationAliasIsName;
        }

        @Override
        public Void visitId(TypeDefinition.Prop.Id id) {
            cols.add(dialect.quoteIdentifier(id.columnName()) + " AS " + dialect.quoteIdentifier(id.name()));
            return null;
        }

        @Override
        public Void visitScalar(TypeDefinition.Prop.Scalar scalar) {
            cols.add(dialect.quoteIdentifier(scalar.columnName()) + " AS " + dialect.quoteIdentifier(scalar.name()));
            return null;
        }

        @Override
        public Void visitAssociation(TypeDefinition.Prop.Association assoc) {
            if (assoc.fkColumn() != null) {
                String alias = associationAliasIsName ? assoc.name() : assoc.fkColumn();
                cols.add(dialect.quoteIdentifier(assoc.fkColumn()) + " AS " + dialect.quoteIdentifier(alias));
            }
            return null;
        }

        @Override
        public Void visitVersion(TypeDefinition.Prop.Version version) {
            return null;
        }

        @Override
        public Void visitEmbedded(TypeDefinition.Prop.Embedded embedded) {
            return null;
        }

        @Override
        public Void visitEntityCollection(TypeDefinition.Prop.EntityCollection entityCollection) {
            return null;
        }

        @Override
        public Void visitElementCollection(TypeDefinition.Prop.ElementCollection elementCollection) {
            return null;
        }
    }

    // ------------------------------------------------------------------- splice primitives

    public static String qualify(SqlDialect dialect, ColumnRef columnRef, String rootTable) {
        String qualifier = columnRef.table() != null ? columnRef.table() : rootTable;
        if (qualifier == null) {
            throw new IllegalStateException(
                    "Cannot qualify column '" + columnRef.column() + "': no owning table or root table");
        }
        return SqlFragment.qualified(dialect, qualifier, columnRef.column());
    }

    public static String existsConjunct(SqlDialect dialect, Predicate predicate, ColumnPlacement placement) {
        List<JoinEdge> edges = placement.edges();
        JoinEdge join = edges.getLast();
        String owner = join.ownerTable();
        return "EXISTS (SELECT 1 FROM " + dialect.quoteIdentifier(join.childTable()) + " "
                + dialect.quoteIdentifier(join.childTable())
                + " WHERE " + dialect.quoteIdentifier(join.childTable()) + "."
                + dialect.quoteIdentifier(join.childColumn())
                + " = " + dialect.quoteIdentifier(owner) + "." + dialect.quoteIdentifier(join.ownerColumn())
                + " AND " + renderPredicate(dialect, predicate, placement) + ")";
    }

    public static String aggregateSortTerm(SqlDialect dialect, TypeDefinition typeModel, ColumnPlacement placement,
                                           boolean ascending) {
        List<JoinEdge> edges = placement.edges();
        if (edges.isEmpty()) {
            throw new IllegalArgumentException("aggregate sort term requires a relation path");
        }
        JoinEdge head = edges.getFirst();
        String owner = head.ownerTable();
        StringBuilder sb = new StringBuilder("(SELECT ")
                .append(ascending ? "MIN(" : "MAX(")
                .append(qualify(dialect, placement.leaf(), typeModel.tableName()))
                .append(") FROM ").append(dialect.quoteIdentifier(head.childTable()))
                .append(' ').append(dialect.quoteIdentifier(head.childTable()));
        sb.append(SqlFragment.joins(dialect, edges.subList(1, edges.size())));
        sb.append(" WHERE ").append(dialect.quoteIdentifier(head.childTable())).append('.')
                .append(dialect.quoteIdentifier(head.childColumn()))
                .append(" = ").append(dialect.quoteIdentifier(owner)).append('.')
                .append(dialect.quoteIdentifier(head.ownerColumn()))
                .append(')');
        return sb.toString();
    }

    public static String renderPredicate(SqlDialect dialect, Predicate predicate, ColumnPlacement placement) {
        ColumnRef leaf = placement.leaf();
        String col = placement.local() && leaf.table() == null
                ? dialect.quoteIdentifier(leaf.column())
                : qualify(dialect, leaf, null);
        return renderOperator(predicate.operator(), col, predicate.param(), predicate.ignoreCase());
    }

    public static String renderPredicate(SqlDialect dialect, Predicate predicate, ColumnPlacement placement,
                                         String rootTable, boolean qualifyRoot) {
        ColumnRef leaf = placement.leaf();
        boolean localRootColumn = isLocalTo(leaf, rootTable);
        String col = localRootColumn && !qualifyRoot
                ? dialect.quoteIdentifier(leaf.column())
                : qualify(dialect, leaf, rootTable);
        return renderOperator(predicate.operator(), col, predicate.param(), predicate.ignoreCase());
    }

    public static String orderTerm(String renderedColumn, SortSpec sort, boolean existentialSort) {
        boolean fold = sort.ignoreCase() && !existentialSort;
        return (fold ? "LOWER(" + renderedColumn + ")" : renderedColumn)
                + (sort.ascending() ? " ASC" : " DESC");
    }

    public static ColumnPlacement resolveColumn(TypeDefinition typeModel, String path) {
        try {
            return typeModel.resolve(path);
        } catch (UnknownAttributePathException unknownPath) {
            return new ColumnPlacement(new ColumnRef.Simple(Names.columnBase(path), typeModel.tableName()));
        }
    }

    public static boolean isLocalTo(ColumnRef leaf, String table) {
        return leaf.table() == null || leaf.table().equals(table);
    }

    public static boolean isExistential(TypeDefinition typeModel, String path) {
        return pathHead(path) != null && typeModel.isToManyPath(path);
    }

    public static String pathHead(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? null : path.substring(0, dot);
    }

    public static String paramName(String path) {
        return path.replace('.', '_');
    }

    public static List<String> paramNames(Operator operator, String param) {
        return switch (operator) {
            case IS_NULL, IS_NOT_NULL, TRUE, FALSE -> List.of();
            case BETWEEN, NOT_BETWEEN -> List.of(param + "_from", param + "_to");
            default -> List.of(param);
        };
    }

    public static String attributePath(TypeDefinition typeModel, ColumnRef column) {
        Map<String, ColumnRef> refs = typeModel.columnRefs();
        return refs.entrySet().stream()
                .filter(entry -> entry.getValue().column().equals(column.column()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No logical attribute path maps to column '" + column.column() + "'"));
    }

    private static String renderOperator(Operator operator, String column, String param, boolean ignoreCase) {
        return ignoreCase ? renderIgnoreCase(operator, column, param) : renderOperator(operator, column, param);
    }

    private static String renderIgnoreCase(Operator operator, String column, String param) {
        String lowerCol = "LOWER(" + column + ")";
        String lowerParam = "LOWER(:" + param + ")";
        return switch (operator) {
            case EQ -> lowerCol + " = " + lowerParam;
            case NOT_EQ -> lowerCol + " <> " + lowerParam;
            case LIKE, CONTAINS, STARTS_WITH, ENDS_WITH -> lowerCol + " LIKE " + lowerParam;
            case NOT_LIKE, NOT_CONTAINS, NOT_STARTS_WITH, NOT_ENDS_WITH -> lowerCol + " NOT LIKE " + lowerParam;
            default -> renderOperator(operator, column, param);
        };
    }

    private static String renderOperator(Operator operator, String column, String param) {
        return switch (operator) {
            case EQ -> column + " = :" + param;
            case NOT_EQ -> column + " <> :" + param;
            case LESS_THAN -> column + " < :" + param;
            case LESS_THAN_EQUAL -> column + " <= :" + param;
            case GREATER_THAN -> column + " > :" + param;
            case GREATER_THAN_EQUAL -> column + " >= :" + param;
            case LIKE, CONTAINS, STARTS_WITH, ENDS_WITH -> column + " LIKE :" + param;
            case NOT_LIKE, NOT_CONTAINS, NOT_STARTS_WITH, NOT_ENDS_WITH -> column + " NOT LIKE :" + param;
            case BETWEEN -> {
                List<String> bounds = paramNames(operator, param);
                yield column + " BETWEEN :" + bounds.getFirst() + " AND :" + bounds.get(1);
            }
            case NOT_BETWEEN -> {
                List<String> bounds = paramNames(operator, param);
                yield column + " NOT BETWEEN :" + bounds.getFirst() + " AND :" + bounds.get(1);
            }
            case IN -> column + " IN (:" + param + ")";
            case NOT_IN -> column + " NOT IN (:" + param + ")";
            case IS_NULL -> column + " IS NULL";
            case IS_NOT_NULL -> column + " IS NOT NULL";
            case TRUE -> column + " = TRUE";
            case FALSE -> column + " = FALSE";
        };
    }
}
