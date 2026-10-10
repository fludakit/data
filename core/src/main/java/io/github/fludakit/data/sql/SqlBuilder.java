package io.github.fludakit.data.sql;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.AttributePathResolver;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.EntityMetadata;
import io.github.fludakit.data.metadata.JoinRef;
import io.github.fludakit.data.metadata.TableSchema;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.SortSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The engine's ONE SQL renderer, now a fluent builder. Bind the {@link TableSchema}, the
 * entity-rooted {@link AttributePathResolver} and — for a structured query — the logical
 * {@link QueryDefinition} in the constructor, optionally override the {@link SqlDialect}, then call a
 * terminal renderer:
 *
 * <pre>
 * new SqlBuilder(schema, resolver, model).dialect(H2Dialect.INSTANCE).select();
 * new SqlBuilder(schema, resolver).insert(false);
 * new SqlBuilder(schema).deleteById();
 * </pre>
 *
 * <p>Physical lifecycle statements use a {@link TableSchema} for one table; structured queries also use
 * an {@link AttributePathResolver} for entity-rooted paths. {@link #select()} / {@link #count()} /
 * {@link #delete()} render a whole query method from its {@link QueryDefinition}; the splice primitives
 * ({@link #qualify(ColumnRef)}, {@link #joinClause(List)}, {@link #aggregateSortTerm(ColumnRef, boolean)},
 * ...) are the same rendering pieces exposed as instance methods for callers that assemble dynamic SQL
 * at runtime (the generated paged readers). Generated SQL syntax is supplied by the {@link SqlDialect};
 * {@link H2Dialect} is the default and only shipped backend. {@link #paramName(String)} is the one pure
 * (stateless) helper kept static.</p>
 */
public final class SqlBuilder {

    private final TableSchema schema;
    private final AttributePathResolver<?> resolver;
    private final QueryDefinition model;
    private SqlDialect dialect;

    /** A schema-only builder for lifecycle statements and splice primitives. */
    public SqlBuilder(TableSchema schema) {
        this(schema, null, null);
    }

    /** A convenience for generated code: an {@link EntityMetadata} is both schema and resolver. */
    public SqlBuilder(EntityMetadata<?> metadata) {
        this(metadata, metadata, null);
    }

    /** A schema + resolver builder for lifecycle statements that bind logical paths. */
    public SqlBuilder(TableSchema schema, AttributePathResolver<?> resolver) {
        this(schema, resolver, null);
    }

    /** A full structured-query builder. */
    public SqlBuilder(TableSchema schema, AttributePathResolver<?> resolver, QueryDefinition model) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.resolver = resolver;
        this.model = model;
        this.dialect = H2Dialect.INSTANCE;
    }

    /** Overrides the dialect used by every subsequent rendering. Returns {@code this}. */
    public SqlBuilder dialect(SqlDialect dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        return this;
    }

    // --------------------------------------------------------------- structured terminals

    /** Renders the builder's query model as a SELECT (FIND). */
    public String select() {
        return select(null);
    }

    /** Renders the query model as a SELECT carrying a joined full-materialisation projection. */
    public String select(String extraProjection) {
        return build(requireModel(), extraProjection);
    }

    /** Renders the query model as a COUNT or EXISTS. */
    public String count() {
        return build(requireModel(), null);
    }

    /** Renders the query model as a DELETE. */
    public String delete() {
        return build(requireModel(), null);
    }

    // --------------------------------------------------------------- lifecycle terminals

    /** Renders a classic no-join SELECT over the builder's schema. */
    public String selectBy(List<Predicate> predicates, List<SortSpec> sorts, int limit, String cursorClause) {
        return selectBy(schema, predicates, sorts, limit, cursorClause);
    }

    /** Renders a classic no-join COUNT over the builder's schema. */
    public String countBy(List<Predicate> predicates) {
        return countBy(schema, predicates);
    }

    /** Renders a classic no-join DELETE over the builder's schema. */
    public String deleteBy(List<Predicate> predicates) {
        return deleteBy(schema, predicates);
    }

    /** Renders an INSERT including the identifier column. */
    public String insert() {
        return insert(true);
    }

    /** Renders an INSERT; {@code includeId=false} omits a database-generated identity column. */
    public String insert(boolean includeId) {
        return insert(schema, requireResolver(), includeId);
    }

    /** Renders an UPDATE by identifier. */
    public String update() {
        return update(schema, requireResolver());
    }

    /** Renders a DELETE by identifier. */
    public String deleteById() {
        return deleteById(schema);
    }

    // --------------------------------------------------------------- splice primitives

    /** Qualifies a table-local column by its owner alias, owner table, or the schema's root table. */
    public String qualify(ColumnRef ref) {
        return qualify(dialect, ref, schema.tableName());
    }

    /** Qualifies a column against an explicit root table. */
    public String qualify(ColumnRef ref, String rootTable) {
        return qualify(dialect, ref, rootTable);
    }

    /** The deduplicated {@code LEFT JOIN} chain these refs require (empty when nothing joins). */
    public String joinClause(List<ColumnRef> refs) {
        return joinClause(dialect, refs);
    }

    /**
     * Resolves an entity-rooted logical attribute path to its table-local physical column. A path
     * that traverses a relationship (its ref carries joins) has no single table-local column and is
     * rejected, so callers fall back to relation-aware resolution (e.g. an aggregate sort term).
     */
    public String resolveColumn(String path) {
        ColumnRef ref = requireResolver().columnRef(path);
        if (!ref.joins().isEmpty()) {
            throw new IllegalArgumentException(
                    "'" + path + "' traverses a relationship and has no table-local column");
        }
        return ref.column();
    }

    /** Whether the first segment of an entity-rooted path is a to-many relation or collection. */
    public boolean isToManyPath(String path) {
        return requireResolver().isToManyPath(path);
    }

    /** The correlated {@code MIN}/{@code MAX} scalar subquery a to-many sort orders by. */
    public String aggregateSortTerm(ColumnRef ref, boolean ascending) {
        return aggregateSortTerm(dialect, schema, ref, ascending);
    }

    /** The EXISTS conjunct a predicate on an existential (or DELETE-traversed) ref renders. */
    public String existsConjunct(Predicate predicate, ColumnRef ref) {
        return existsConjunct(dialect, predicate, ref);
    }

    /** Renders a predicate with its column qualified by {@code ref}. */
    public String render(Predicate predicate, ColumnRef ref) {
        return render(dialect, predicate, ref);
    }

    /** The root-table projection/join shell ({@code SELECT ... FROM ... LEFT JOIN ...}). */
    public String qualifiedRootSelect(List<ColumnRef> traversedToOne, boolean qualified, String extraProjection) {
        return qualifiedRootSelect(dialect, schema, traversedToOne, qualified, extraProjection);
    }

    /**
     * Maps a dot-separated property path to the named-parameter token used in the generated SQL
     * (e.g. {@code "address.street"} becomes {@code "address_street"}). Callers that bind parameters
     * programmatically must use the same mapping so the emitted {@code .param(...)} names line up.
     */
    public static String paramName(String path) {
        return path.replace('.', '_');
    }

    // --------------------------------------------------------------- internal rendering

    private QueryDefinition requireModel() {
        if (model == null) {
            throw new IllegalStateException("this SqlBuilder has no query model — construct it with "
                    + "SqlBuilder(TableSchema, AttributePathResolver, QueryDefinition)");
        }
        return model;
    }

    private AttributePathResolver<?> requireResolver() {
        if (resolver == null) {
            throw new IllegalStateException("this SqlBuilder has no attribute resolver — construct it with "
                    + "SqlBuilder(TableSchema, AttributePathResolver[, QueryDefinition])");
        }
        return resolver;
    }

    private String selectBy(TableSchema md, List<Predicate> predicates, List<SortSpec> sorts, int limit, String cursorClause) {
        String cols = md.columns().stream().map(c -> dialect.quoteIdentifier(c.column())).collect(Collectors.joining(", "));
        StringBuilder sb = new StringBuilder("SELECT ").append(cols).append(" FROM ")
                .append(dialect.quoteIdentifier(md.tableName()));
        appendWhere(sb, predicates, cursorClause);
        sb.append(orderBy(md, sorts));
        if (limit > 0) sb.append(dialect.limitClause(Integer.toString(limit)));
        return sb.toString();
    }

    private String countBy(TableSchema md, List<Predicate> predicates) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM ").append(dialect.quoteIdentifier(md.tableName()));
        appendWhere(sb, predicates, null);
        return sb.toString();
    }

    /**
     * Builds an INSERT. When {@code includeId} is false the identifier column is omitted so the
     * database can generate it (identity / auto-increment); binding an explicit NULL to an identity
     * column is rejected by most databases, including H2.
     */
    private String insert(TableSchema md, AttributePathResolver<?> attributes) {
        return insert(md, attributes, true);
    }

    private String insert(TableSchema md, AttributePathResolver<?> attributes, boolean includeId) {
        List<ColumnRef> cols = md.columns();
        if (!includeId) {
            cols = cols.stream()
                    .filter(c -> md.idColumn() == null || !c.column().equals(md.idColumn()))
                    .collect(Collectors.toList());
        }
        String names = cols.stream().map(c -> dialect.quoteIdentifier(c.column())).collect(Collectors.joining(", "));
        String params = cols.stream().map(c -> ":" + paramName(attributePath(attributes, c)))
                .collect(Collectors.joining(", "));
        return "INSERT INTO " + dialect.quoteIdentifier(md.tableName()) + " (" + names + ") VALUES (" + params + ")";
    }

    private String update(TableSchema md, AttributePathResolver<?> attributes) {
        List<ColumnRef> cols = md.columns();
        String idCol = dialect.quoteIdentifier(md.idColumn());
        String sets = cols.stream()
                .filter(c -> !c.column().equals(md.idColumn()))
                .map(c -> dialect.quoteIdentifier(c.column()) + " = :"
                        + paramName(attributePath(attributes, c)))
                .collect(Collectors.joining(", "));
        return "UPDATE " + dialect.quoteIdentifier(md.tableName()) + " SET " + sets + " WHERE " + idCol + " = :id";
    }

    private static <A> String attributePath(AttributePathResolver<A> attributes, ColumnRef column) {
        return attributes.columnRefs().entrySet().stream()
                .filter(entry -> entry.getValue().equals(column))
                .map(entry -> attributes.attributePath(entry.getKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No logical attribute path maps to column '" + column.column() + "'"));
    }

    private String deleteById(TableSchema md) {
        return "DELETE FROM " + dialect.quoteIdentifier(md.tableName()) + " WHERE "
                + dialect.quoteIdentifier(md.idColumn()) + " = :id";
    }

    private String deleteBy(TableSchema md, List<Predicate> predicates) {
        StringBuilder sb = new StringBuilder("DELETE FROM ").append(dialect.quoteIdentifier(md.tableName()));
        appendWhere(sb, predicates, null);
        return sb.toString();
    }

    /**
     * The ONE statement layout for a logical query model: predicate and sort paths are resolved
     * through the supplied entity attribute resolver, then rendered against physical table columns
     * and relationship joins.
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
    private String build(QueryDefinition model, String extraProjection) {
        TableSchema md = schema;
        AttributePathResolver<?> attributes = requireResolver();
        Operation operation = model.operation();
        boolean deleteMode = operation == Operation.DELETE;
        boolean countMode = operation == Operation.COUNT || operation == Operation.EXISTS;
        List<Predicate> predicates = model.predicates();
        List<SortSpec> sorts = model.paging() == PagingMode.NONE
                ? model.sorts() : List.of();
        int limit = model.paging() == PagingMode.NONE ? model.limit() : 0;

        List<ColumnRef> joinRefs = new ArrayList<>();
        List<Integer> existentialIndices = new ArrayList<>();
        List<Predicate> plain = new ArrayList<>(predicates.size());
        List<ColumnRef> predicateRefs = new ArrayList<>(predicates.size());
        boolean legacyCompatible = true;
        for (int i = 0; i < predicates.size(); i++) {
            Predicate predicate = predicates.get(i);
            ColumnRef ref = resolveColumnRef(md, attributes, predicate.property());
            plain.add(predicate);
            predicateRefs.add(ref);
            legacyCompatible &= legacyCompatible(ref, predicate.property(), md);
            if (isExistential(attributes, predicate.property()) || (deleteMode && !ref.joins().isEmpty())) {
                existentialIndices.add(i);
            } else {
                joinRefs.add(ref);
            }
        }
        List<ColumnRef> sortRefs = new ArrayList<>(sorts.size());
        List<Boolean> existentialSorts = new ArrayList<>(sorts.size());
        for (SortSpec sort : sorts) {
            ColumnRef ref = resolveColumnRef(md, attributes, sort.property());
            boolean existential = isExistential(attributes, sort.property());
            sortRefs.add(ref);
            existentialSorts.add(existential);
            legacyCompatible &= legacyCompatible(ref, sort.property(), md);
            if (!existential) {
                joinRefs.add(ref);
            }
        }
        if (existentialIndices.isEmpty() && !existentialSorts.contains(Boolean.TRUE)
                && !hasJoins(joinRefs) && legacyCompatible) {
            if (countMode) {
                return countBy(md, plain);
            }
            if (deleteMode) {
                return deleteBy(md, plain);
            }
            return selectBy(md, plain, sorts, limit, null);
        }
        boolean spliceJoins = hasJoins(joinRefs);
        StringBuilder sb = new StringBuilder();
        if (countMode) {
            sb.append("SELECT COUNT(*) FROM ").append(dialect.quoteIdentifier(md.tableName()))
                    .append(joinClause(dialect, joinRefs));
        } else if (deleteMode) {
            sb.append("DELETE FROM ").append(dialect.quoteIdentifier(md.tableName()))
                    .append(joinClause(dialect, joinRefs));
        } else {
            // Joined find: every root column is qualified by the root table. Unqualified bare names
            // become ambiguous the moment a join adds a table sharing a column name (e.g. the target's
            // own "id"), and a full-materialisation read joins exactly such a table. Qualifying keeps
            // each label = its physical column, so the generated row mapper still reads it by name.
            sb.append(qualifiedRootSelect(dialect, schema, joinRefs, spliceJoins, extraProjection));
        }
        List<String> conjuncts = new ArrayList<>();
        for (int i = 0; i < plain.size(); i++) {
            if (!existentialIndices.contains(i)) {
                conjuncts.add(render(dialect, plain.get(i), predicateRefs.get(i),
                        md.tableName(), spliceJoins));
            }
        }
        for (int i : existentialIndices) {
            conjuncts.add(existsConjunct(dialect, plain.get(i), predicateRefs.get(i)));
        }
        if (!conjuncts.isEmpty()) {
            sb.append(" WHERE ").append(String.join(" AND ", conjuncts));
        }
        if (!sorts.isEmpty()) {
            StringBuilder orderBy = new StringBuilder();
            for (int i = 0; i < sorts.size(); i++) {
                SortSpec sort = sorts.get(i);
                ColumnRef ref = sortRefs.get(i);
                String term = Boolean.TRUE.equals(existentialSorts.get(i))
                        ? aggregateSortTerm(dialect, schema, ref, sort.ascending())
                        : ref.joins().isEmpty() && ref.ownerAlias() == null
                                && (ref.ownerTableName() == null || ref.ownerTableName().equals(md.tableName()))
                                ? dialect.quoteIdentifier(ref.column())
                                : qualify(dialect, ref, md.tableName());
                if (orderBy.length() > 0) {
                    orderBy.append(", ");
                }
                orderBy.append(term).append(sort.ascending() ? " ASC" : " DESC");
            }
            sb.append(" ORDER BY ").append(orderBy);
        }
        if (limit > 0) {
            sb.append(dialect.limitClause(Integer.toString(limit)));
        }
        return sb.toString();
    }

    private ColumnRef resolveColumnRef(TableSchema schema, AttributePathResolver<?> attributes, String path) {
        try {
            return attributes.columnRef(path);
        } catch (IllegalArgumentException unknownPath) {
            return new ColumnRef(Names.columnName(path), schema.tableName());
        }
    }

    private boolean legacyCompatible(ColumnRef ref, String path, TableSchema schema) {
        return ref.joins().isEmpty() && ref.ownerAlias() == null
                && (ref.ownerTableName() == null || ref.ownerTableName().equals(schema.tableName()))
                && ref.column().equals(Names.columnName(path));
    }

    private static boolean isExistential(AttributePathResolver<?> attributes, String path) {
        return pathHead(path) != null && attributes.isToManyPath(path);
    }

    /** Renders the ORDER BY clause for the given sort paths, resolving each through the resolver. */
    public String orderBy(List<SortSpec> sorts) {
        if (sorts.isEmpty()) return "";
        return " ORDER BY " + sorts.stream()
                .map(s -> dialect.quoteIdentifier(requireResolver().columnRef(s.property()).column())
                        + (s.ascending() ? " ASC" : " DESC"))
                .collect(Collectors.joining(", "));
    }

    private String orderBy(TableSchema table, List<SortSpec> sorts) {
        if (sorts.isEmpty()) return "";
        return " ORDER BY " + sorts.stream()
                .map(s -> dialect.quoteIdentifier(s.property().replace('.', '_'))
                        + (s.ascending() ? " ASC" : " DESC"))
                .collect(Collectors.joining(", "));
    }

    /**
     * Qualifies a table-local column by its owner alias, owner table name, or the supplied root.
     */
    private static String qualify(SqlDialect dialect, ColumnRef ref, String rootTable) {
        String qualifier;
        if (ref.ownerAlias() != null) {
            qualifier = ref.ownerAlias();
        } else if (ref.ownerTableName() != null) {
            qualifier = ref.ownerTableName();
        } else if (rootTable != null) {
            qualifier = rootTable;
        } else {
            throw new IllegalStateException(
                    "Cannot qualify column '" + ref.column() + "': no owning table or root table");
        }
        return dialect.quoteIdentifier(qualifier) + "." + dialect.quoteIdentifier(ref.column());
    }

    /**
     * The deduplicated {@code LEFT JOIN} chain these refs require. Each edge carries its own owner
     * and child table/alias, so rendering does not infer table identity from list position.
     * Each fragment is
     * {@code  LEFT JOIN "<table>" AS "<alias>" ON <qualifier>."<parentColumn>" = "<alias>"."<childColumn>";}
     * Returns {@code ""} when no ref carries a join.
     */
    private static String joinClause(SqlDialect dialect, List<ColumnRef> refs) {
        Map<String, JoinRef> byAlias = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        for (ColumnRef ref : refs) {
            for (JoinRef join : ref.joins()) {
                if (byAlias.putIfAbsent(join.childAlias(), join) != null) continue;
                String ownerQualifier = join.ownerAlias() == null
                        ? join.ownerTableName() : join.ownerAlias();
                String owner = dialect.quoteIdentifier(ownerQualifier);
                String alias = dialect.quoteIdentifier(join.childAlias());
                sb.append(" LEFT JOIN ").append(dialect.quoteIdentifier(join.childTableName()))
                        .append(" AS ").append(alias)
                        .append(" ON ").append(owner).append('.').append(dialect.quoteIdentifier(join.ownerColumn()))
                        .append(" = ").append(alias).append('.').append(dialect.quoteIdentifier(join.childColumn()));
            }
        }
        return sb.toString();
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
        return renderOperator(p.operator(), dialect.quoteIdentifier(Names.columnName(p.property())), p.param());
    }

    /**
     * Renders a predicate with its column qualified by {@code ref}: last-join alias, then
     * explicit table, and fully-local refs stay unqualified for legacy parity.
     */
    private static String render(SqlDialect dialect, Predicate p, ColumnRef ref) {
        String col = ref.joins().isEmpty() && ref.ownerAlias() == null
                && ref.ownerTableName() == null
                ? dialect.quoteIdentifier(ref.column())
                : qualify(dialect, ref, null);
        return renderOperator(p.operator(), col, p.param());
    }

    private static String render(SqlDialect dialect, Predicate predicate, ColumnRef ref,
                                 String rootTable, boolean qualifyRoot) {
        boolean localRootColumn = ref.joins().isEmpty() && ref.ownerAlias() == null
                && (ref.ownerTableName() == null || rootTable.equals(ref.ownerTableName()));
        String col = localRootColumn && !qualifyRoot
                ? dialect.quoteIdentifier(ref.column())
                : qualify(dialect, ref, rootTable);
        return renderOperator(predicate.operator(), col, predicate.param());
    }

    /**
     * The EXISTS conjunct a predicate on an existential (to-many head) — or on ANY traversed ref in a
     * DELETE — renders instead of a joined WHERE term: the ref's LAST join edge supplies the child
     * table, the relation-property alias and the alias-side column; the correlation closes on the
     * edge's root-side column ({@code parentColumn}) — the root id for a to-many head, the owner-side
     * FK for a to-one head. The leaf term is {@link #render(Predicate, ColumnRef)}'s alias-qualified
     * rendering, so the named parameter and the caller's binding are unchanged.
     */
    private static String existsConjunct(SqlDialect dialect, Predicate p, ColumnRef ref) {
        JoinRef join = ref.joins().get(ref.joins().size() - 1);
        String ownerQualifier = join.ownerAlias() == null ? join.ownerTableName() : join.ownerAlias();
        return "EXISTS (SELECT 1 FROM " + dialect.quoteIdentifier(join.childTableName()) + " "
                + dialect.quoteIdentifier(join.childAlias())
                + " WHERE " + dialect.quoteIdentifier(join.childAlias()) + "." + dialect.quoteIdentifier(join.childColumn())
                + " = " + dialect.quoteIdentifier(ownerQualifier) + "." + dialect.quoteIdentifier(join.ownerColumn())
                + " AND " + render(dialect, p, ref) + ")";
    }

    /** The first segment of an entity-rooted path, or {@code null} when it has no child segment. */
    private static String pathHead(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? null : path.substring(0, dot);
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
     * The correlated scalar subquery a to-many sort orders by: {@code MIN} of the leaf column for an
     * ascending key, {@code MAX} for a descending one — exactly the edge the descriptor-driven cursor
     * key reduction collapses the value list to, so seek and sort agree. The head join opens
     * the subquery {@code FROM} (alias WITHOUT {@code AS}, mirroring nothing else on the statement);
     * deeper edges chain inside as the same {@code LEFT JOIN ... AS ... ON ...} fragments
     * {@link #joinClause} emits, qualified per-path. Correlation is the head's
     * {@code childFk = rootTable.rootId} pair taken from its {@link JoinRef}. Every identifier goes
     * through {@link Names#quote}; the result is used VERBATIM as an ORDER BY / seek term.
     */
    private static String aggregateSortTerm(SqlDialect dialect, TableSchema md, ColumnRef ref, boolean ascending) {
        if (ref.joins().isEmpty()) {
            throw new IllegalArgumentException(
                    "aggregate sort term requires a relation path");
        }
        JoinRef head = ref.joins().get(0);
        String ownerQualifier = head.ownerAlias() == null ? head.ownerTableName() : head.ownerAlias();
        StringBuilder sb = new StringBuilder("(SELECT ")
                .append(ascending ? "MIN(" : "MAX(")
                .append(qualify(dialect, ref, md.tableName()))
                .append(") FROM ").append(dialect.quoteIdentifier(head.childTableName()))
                .append(' ').append(dialect.quoteIdentifier(head.childAlias()));
        for (int i = 1; i < ref.joins().size(); i++) {
            JoinRef join = ref.joins().get(i);
            String joinOwner = join.ownerAlias() == null ? join.ownerTableName() : join.ownerAlias();
            sb.append(" LEFT JOIN ").append(dialect.quoteIdentifier(join.childTableName()))
                    .append(" AS ").append(dialect.quoteIdentifier(join.childAlias()))
                    .append(" ON ").append(dialect.quoteIdentifier(joinOwner)).append('.')
                    .append(dialect.quoteIdentifier(join.ownerColumn()))
                    .append(" = ").append(dialect.quoteIdentifier(join.childAlias())).append('.')
                    .append(dialect.quoteIdentifier(join.childColumn()));
        }
        sb.append(" WHERE ").append(dialect.quoteIdentifier(head.childAlias())).append('.')
                .append(dialect.quoteIdentifier(head.childColumn()))
                .append(" = ").append(dialect.quoteIdentifier(ownerQualifier)).append('.')
                .append(dialect.quoteIdentifier(head.ownerColumn()))
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
    private static String qualifiedRootSelect(SqlDialect dialect, TableSchema md, List<ColumnRef> traversedToOne,
                                              boolean qualified, String extraProjection) {
        if (!qualified && extraProjection != null && !extraProjection.isEmpty()) {
            throw new IllegalStateException("bare projection cannot carry extraProjection");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ").append(md.columns().stream()
                .map(c -> qualified ? qualifiedColumn(dialect, md.tableName(), c.column())
                        : dialect.quoteIdentifier(c.column()))
                .collect(Collectors.joining(", ")));
        if (extraProjection != null && !extraProjection.isEmpty()) {
            sb.append(", ").append(extraProjection);
        }
        sb.append(" FROM ").append(dialect.quoteIdentifier(md.tableName()));
        sb.append(joinClause(dialect, traversedToOne));
        return sb.toString();
    }

    private static String qualifiedColumn(SqlDialect dialect, String qualifier, String column) {
        return dialect.quoteIdentifier(qualifier) + "." + dialect.quoteIdentifier(column);
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
