package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.processor.support.MethodOverrides;
import io.github.fludakit.data.query.LikePattern;
import io.github.fludakit.data.query.LimitSpec;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;
import io.github.fludakit.data.sql.H2Dialect;
import io.github.fludakit.data.sql.SqlBuilder;
import io.github.fludakit.data.sql.SqlDialect;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates method implementations for finder, count, exists, and deleteBy methods.
 *
 * <p>Dispatches on {@link Operation} and {@link PagingMode}:</p>
 * <ul>
 *   <li>{@code FIND + NONE} &rarr; plain finder returning a single entity, list, set, etc.</li>
 *   <li>{@code FIND + OFFSET} &rarr; offset-paged finder returning {@code Page<E>}</li>
 *   <li>{@code FIND + CURSOR} &rarr; cursor-paged finder returning {@code CursoredPage<E>}</li>
 *   <li>{@code COUNT} &rarr; count query returning {@code long}</li>
 *   <li>{@code EXISTS} &rarr; existence check returning {@code boolean}</li>
 *   <li>{@code DELETE} (with predicates) &rarr; bulk delete returning {@code long}</li>
 * </ul>
 */
public final class FindEmitter {

    private static final ClassName PAGE_REQUEST = ClassName.get("jakarta.data.page", "PageRequest");
    private static final ClassName SIMPLE_PAGE = ClassName.get("io.github.fludakit.data.page", "SimplePage");
    private static final ClassName SIMPLE_CURSORED_PAGE = ClassName.get("io.github.fludakit.data.page", "SimpleCursoredPage");
    private static final ClassName CURSOR = ClassName.get("jakarta.data.page", "PageRequest", "Cursor");
    private static final ClassName CURSOR_KEYS = ClassName.get("io.github.fludakit.data.sql", "CursorKeys");
    private static final ClassName VALUE_ACCESS = ClassName.get("io.github.fludakit.data.metadata", "ValueAccess");

    private final SqlDialect dialect;
    private final Types types;
    private final DeclaredType baseRepositoryType;
    private final DeclaredType repositoryOwnType;

    public FindEmitter(SqlDialect dialect, Types types, DeclaredType baseRepositoryType, DeclaredType repositoryOwnType) {
        this.dialect = dialect;
        this.types = types;
        this.baseRepositoryType = baseRepositoryType;
        this.repositoryOwnType = repositoryOwnType;
    }

    /** Default constructor using H2 dialect. */
    public FindEmitter(Types types, DeclaredType baseRepositoryType, DeclaredType repositoryOwnType) {
        this(H2Dialect.INSTANCE, types, baseRepositoryType, repositoryOwnType);
    }

    /**
     * Returns the appropriate DeclaredType for overriding the given method.
     * If the method is declared on the repository interface itself, use the repository's own type.
     * If the method is inherited from a base repository (CrudRepository etc.), use the parameterized base type.
     */
    private DeclaredType resolveTypeFor(ExecutableElement method) {
        if (method.getEnclosingElement().equals(repositoryOwnType.asElement())) {
            return repositoryOwnType;
        }
        return baseRepositoryType;
    }

    /**
     * Generates a method override for a finder, count, exists, deleteBy, or @Query method.
     *
     * @return the method spec, or {@code null} if the method is not handled by this emitter
     */
    public MethodSpec emit(RepositoryContext ctx, MethodContext method) {
        // Custom @Query methods
        if (method.customSql() != null) {
            return emitCustomQuery(ctx, method);
        }

        Operation op = method.operation();
        PagingMode pm = method.pagingMode();

        // Plain finder
        if (op == Operation.FIND && pm == PagingMode.NONE) {
            return emitPlainFinder(ctx, method);
        }

        // Offset-paged finder
        if (op == Operation.FIND && pm == PagingMode.OFFSET) {
            return emitOffsetPagedFinder(ctx, method);
        }

        // Cursor-paged finder
        if (op == Operation.FIND && pm == PagingMode.CURSOR) {
            return emitCursorPagedFinder(ctx, method);
        }

        // Count
        if (op == Operation.COUNT) {
            return emitCount(ctx, method);
        }

        // Exists
        if (op == Operation.EXISTS) {
            return emitExists(ctx, method);
        }

        // DeleteBy (DELETE with predicates, no paging)
        if (op == Operation.DELETE && pm == PagingMode.NONE && !method.predicates().isEmpty()) {
            return emitDeleteBy(ctx, method);
        }

        return null;
    }

    // ── Plain finder ─────────────────────────────────────────────────────

    private MethodSpec emitPlainFinder(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));

        String sql = buildSelectSql(ctx, method);
        String constBase = toSqlConstantName(method.name());
        String constName = ctx.registerConstant(constBase, sql);

        addPlainFinderBody(builder, ctx, method, constName);
        return builder.build();
    }

    private String buildSelectSql(RepositoryContext ctx, MethodContext method) {
        SqlBuilder sqlBuilder = new SqlBuilder(ctx.entity());
        sqlBuilder.dialect(dialect);
        ResultKind rk = method.resultKind() == ResultKind.COUNT || method.resultKind() == ResultKind.EXISTS
                ? ResultKind.LIST : method.resultKind();
        return sqlBuilder.render(new QueryDefinition.Read(
                rk, method.predicates(), method.sorts(),
                PagingMode.NONE, LimitSpec.none(), List.of(), List.of(), method.distinct()));
    }

    private void addPlainFinderBody(MethodSpec.Builder builder, RepositoryContext ctx,
                                     MethodContext method, String sqlConstantName) {
        ClassName rowMapperType = ctx.rowMapperType();
        List<ParamBinding> bindings = method.paramBindings();
        boolean needsHydration = hasToManyCollections(ctx.entity());

        CodeBlock.Builder body = CodeBlock.builder();

        if (needsHydration) {
            // For aggregate roots, capture result in a variable and hydrate before returning
            if (method.resultKind() == ResultKind.STREAM) {
                // Stream: get list first, hydrate, then convert to stream
                body.add("var $L = jdbcClient\n", "result");
                body.add("    .sql($L)\n", sqlConstantName);
                appendParamBindings(body, bindings, method);
                body.add("    .query(new $T())\n", rowMapperType);
                body.add("    .list();\n");
                emitHydrationCall(body, ResultKind.LIST, ctx);
                body.add("return $L.stream();", "result");
            } else {
                body.add("var $L = jdbcClient\n", "result");
                body.add("    .sql($L)\n", sqlConstantName);
                appendParamBindings(body, bindings, method);
                body.add("    .query(new $T())\n", rowMapperType);
                emitTerminalSingle(body, method.resultKind(), ctx);
                body.add(";\n");
                emitHydrationCall(body, method.resultKind(), ctx);
                body.add("return $L;", "result");
            }
        } else {
            body.add("return $L\n", "jdbcClient");
            body.add("    .sql($L)\n", sqlConstantName);
            appendParamBindings(body, bindings, method);
            body.add("    .query(new $T())\n", rowMapperType);
            emitTerminal(body, method.resultKind(), ctx);
            body.add(";");
        }
        builder.addCode(body.build());
    }

    // ── Offset-paged finder ──────────────────────────────────────────────

    private MethodSpec emitOffsetPagedFinder(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));

        String entityFqn = ctx.entity().typeName();
        ClassName entityType = ClassName.bestGuess(entityFqn);
        ClassName rowMapperType = ctx.rowMapperType();

        // Build the base SELECT SQL (without LIMIT/OFFSET — those are runtime values)
        String selectSql = buildSelectSql(ctx, method);
        String selectConstBase = toSqlConstantName(method.name());
        String selectConstName = ctx.registerConstant(selectConstBase, selectSql);

        // Build the COUNT SQL
        String countSql = buildCountSql(ctx, method);
        String countConstBase = toSqlConstantName("COUNT_" + method.name());
        String countConstName = ctx.registerConstant(countConstBase, countSql);

        List<ParamBinding> bindings = method.paramBindings();

        // Find the PageRequest parameter name
        String pageRequestParam = findPageRequestParam(method);

        CodeBlock.Builder body = CodeBlock.builder();

        // long offset = (pageRequest.page() - 1) * pageRequest.size();
        body.addStatement("long $L = ($L.page() - 1) * $L.size()", "offset", pageRequestParam, pageRequestParam);

        // List<Entity> content = jdbcClient.sql(SELECT_SQL + " LIMIT :limit OFFSET :offset")
        //     .param("limit", pageRequest.size())
        //     .param("offset", offset)
        //     .param(...) for each binding
        //     .query(new EntityRowMapper())
        //     .list();
        body.add("$T<$T> $L = jdbcClient\n", List.class, entityType, "content");
        body.add("    .sql($L + $S)\n", selectConstName, dialect.limitOffsetClause(":limit", ":offset"));
        body.add("    .param($S, $L.size())\n", "limit", pageRequestParam);
        body.add("    .param($S, $L)\n", "offset", "offset");
        appendParamBindings(body, bindings, method);
        body.add("    .query(new $T())\n", rowMapperType);
        body.addStatement("    .list()");

        // long total = pageRequest.requestTotal() ? jdbcClient.sql(COUNT_SQL)...single() : 0;
        body.add("long $L = $L.requestTotal() ? jdbcClient\n", "total", pageRequestParam);
        body.add("    .sql($L)\n", countConstName);
        appendParamBindings(body, bindings, method);
        body.addStatement("    .query($T.class).single() : 0", Long.class);

        // return new SimplePage<>(pageRequest, content, total);
        body.addStatement("return new $T<>($L, $L, $L)", SIMPLE_PAGE, pageRequestParam, "content", "total");

        builder.addCode(body.build());
        return builder.build();
    }

    private String buildCountSql(RepositoryContext ctx, MethodContext method) {
        SqlBuilder sqlBuilder = new SqlBuilder(ctx.entity());
        sqlBuilder.dialect(dialect);
        return sqlBuilder.render(new QueryDefinition.Count(method.predicates(), List.of(), false));
    }

    // ── Cursor-paged finder ──────────────────────────────────────────────

    private MethodSpec emitCursorPagedFinder(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));

        String entityFqn = ctx.entity().typeName();
        ClassName entityType = ClassName.bestGuess(entityFqn);
        ClassName rowMapperType = ctx.rowMapperType();
        List<SortSpec> sorts = method.sorts();

        // Detect ENTITY_COLLECTION sorts first (needed for both SQL generation and cursor extraction)
        boolean hasToManySort = false;
        TypeDefinition.Prop.EntityCollection ecRelForHydration = null;
        String toManyPropForHydration = null;

        for (SortSpec sort : sorts) {
            String prop = sort.property();
            if (prop.contains(".")) {
                String firstSegment = prop.substring(0, prop.indexOf('.'));
                for (TypeDefinition.Prop p : ctx.entity().properties()) {
                    if (p instanceof TypeDefinition.Prop.EntityCollection ec && p.name().equals(firstSegment)) {
                        hasToManySort = true;
                        if (ecRelForHydration == null) {
                            ecRelForHydration = ec;
                            toManyPropForHydration = firstSegment;
                        }
                        break;
                    } else if (p instanceof TypeDefinition.Prop.ElementCollection elemCol && p.name().equals(firstSegment)) {
                        hasToManySort = true;
                        if (ecRelForHydration == null) {
                            // ElementCollection doesn't have a definition Entity, skip hydration
                        }
                        break;
                    }
                }
            }
        }

        // Build the base SELECT SQL shell (with LEFT JOIN for TO_MANY hydration)
        SqlBuilder sqlBuilder = new SqlBuilder(ctx.entity());
        sqlBuilder.dialect(dialect);
        String baseSql;
        String joinAlias = null; // Declare here so it's available for seek term generation

        if (hasToManySort && ecRelForHydration != null && ecRelForHydration.target() != null) {
            // For ENTITY_COLLECTION sorts, add a LEFT JOIN to hydrate the relation for cursor extraction.
            // Also add aggregate expressions to SELECT for ORDER BY compatibility with DISTINCT.
            String rootTable = dialect.quoteIdentifier(ctx.entity().tableName());
            String childTable = dialect.quoteIdentifier(ecRelForHydration.target().tableName());
            String fkColumn = dialect.quoteIdentifier(ecRelForHydration.fkColumn());
            String ownerIdColumn = dialect.quoteIdentifier(ctx.entity().idColumn());
            joinAlias = toManyPropForHydration + "_";

            // Build SELECT with root columns AND aggregate expressions for TO_MANY sort terms
            List<String> selectColumns = new ArrayList<>();
            List<String> groupByColumns = new ArrayList<>();
            for (TypeDefinition.Prop prop : ctx.entity().properties()) {
                if (prop instanceof TypeDefinition.Prop.Scalar s) {
                    String qualifiedCol = rootTable + "." + dialect.quoteIdentifier(s.columnName());
                    selectColumns.add(qualifiedCol);
                    groupByColumns.add(qualifiedCol);
                } else if (prop instanceof TypeDefinition.Prop.Id id) {
                    String qualifiedCol = rootTable + "." + dialect.quoteIdentifier(id.columnName());
                    selectColumns.add(qualifiedCol);
                    groupByColumns.add(qualifiedCol);
                } else if (prop instanceof TypeDefinition.Prop.Version v) {
                    String qualifiedCol = rootTable + "." + dialect.quoteIdentifier(v.columnName());
                    selectColumns.add(qualifiedCol);
                    groupByColumns.add(qualifiedCol);
                } else if (prop instanceof TypeDefinition.Prop.Association assoc && assoc.fkColumn() != null) {
                    String qualifiedCol = rootTable + "." + dialect.quoteIdentifier(assoc.fkColumn());
                    selectColumns.add(qualifiedCol);
                    groupByColumns.add(qualifiedCol);
                }
            }

            // Add aggregate expressions for each TO_MANY sort term
            int aggIndex = 0;
            for (SortSpec sort : sorts) {
                String prop = sort.property();
                if (prop.contains(".")) {
                    String firstSegment = prop.substring(0, prop.indexOf('.'));
                    if (firstSegment.equals(toManyPropForHydration)) {
                        String colPart = prop.substring(prop.indexOf('.') + 1).replace('.', '_');
                        String aggFunc = sort.ascending() ? "MIN" : "MAX";
                        String aggExpr = aggFunc + "(" + joinAlias + "." + dialect.quoteIdentifier(colPart) + ") AS " + dialect.quoteIdentifier("agg_" + aggIndex);
                        selectColumns.add(aggExpr);
                        aggIndex++;
                    }
                }
            }

            String selectClause = "SELECT " + String.join(", ", selectColumns);
            String fromClause = "FROM " + rootTable;
            String joinClause = "LEFT JOIN " + childTable + " AS " + joinAlias
                    + " ON " + joinAlias + "." + fkColumn + " = " + rootTable + "." + ownerIdColumn;
            String groupByClause = " GROUP BY " + String.join(", ", groupByColumns);

            baseSql = selectClause + " " + fromClause + " " + joinClause + groupByClause;
        } else {
            // No TO_MANY sort, use standard rendering
            baseSql = sqlBuilder.render(new QueryDefinition.Read(
                    ResultKind.LIST, method.predicates(), List.of(),
                    PagingMode.NONE, LimitSpec.none(), List.of(), List.of(), method.distinct()));
        }

        boolean shellHasWhere = !method.predicates().isEmpty();

        // Build sort terms and properties lists
        List<String> properties = new ArrayList<>();
        List<Boolean> ascendingList = new ArrayList<>();
        // Separate term lists: orderByTerms uses aliases (for ORDER BY), seekTerms uses full expressions (for WHERE)
        List<String> orderByTerms = new ArrayList<>();
        List<String> seekTerms = new ArrayList<>();

        for (SortSpec sort : sorts) {
            String prop = sort.property();
            properties.add(prop);
            ascendingList.add(sort.ascending());

            // Check if this is an ENTITY_COLLECTION path
            if (prop.contains(".")) {
                String firstSegment = prop.substring(0, prop.indexOf('.'));
                for (TypeDefinition.Prop p : ctx.entity().properties()) {
                    if ((p instanceof TypeDefinition.Prop.EntityCollection || p instanceof TypeDefinition.Prop.ElementCollection)
                            && p.name().equals(firstSegment)) {
                        hasToManySort = true;
                        break;
                    }
                }
            }
        }

        // If we have TO_MANY sorts, we need to ensure the id is the last sort key for uniqueness
        if (hasToManySort && !sorts.isEmpty()) {
            SortSpec lastSort = sorts.get(sorts.size() - 1);
            String idPropName = null;
            if (ctx.entity() instanceof TypeDefinition.Entity e) {
                idPropName = e.id().name();
            } else if (ctx.entity() instanceof TypeDefinition.Aggregate a) {
                idPropName = a.id().name();
            }
            if (idPropName != null && !(lastSort.property().equals(idPropName) && lastSort.ascending())) {
                // Add id as the last sort key
                properties.add(idPropName);
                ascendingList.add(true);
            }
        }

        // Build ORDER BY and SEEK terms - aliases for ORDER BY, full expressions for WHERE
        int aggIndex = 0;
        for (int i = 0; i < sorts.size(); i++) {
            SortSpec sort = sorts.get(i);
            String prop = sort.property();
            String orderByTerm;
            String seekTerm;

            if (prop.contains(".")) {
                String firstSegment = prop.substring(0, prop.indexOf('.'));
                boolean isToManyPath = false;
                TypeDefinition.Prop.EntityCollection ecRel = null;
                TypeDefinition.Prop.ElementCollection elemColRel = null;

                for (TypeDefinition.Prop p : ctx.entity().properties()) {
                    if (p instanceof TypeDefinition.Prop.EntityCollection entityCol && p.name().equals(firstSegment)) {
                        isToManyPath = true;
                        ecRel = entityCol;
                        break;
                    } else if (p instanceof TypeDefinition.Prop.ElementCollection ec && p.name().equals(firstSegment)) {
                        isToManyPath = true;
                        elemColRel = ec;
                        break;
                    }
                }

                if (isToManyPath && ((ecRel != null && ecRel.target() != null) || elemColRel != null)) {
                    String colPart = prop.substring(prop.indexOf('.') + 1).replace('.', '_');
                    String childTable = ecRel != null && ecRel.target() != null
                        ? dialect.quoteIdentifier(ecRel.target().tableName())
                        : dialect.quoteIdentifier(elemColRel.childTable());
                    String fkColumn = ecRel != null
                        ? dialect.quoteIdentifier(ecRel.fkColumn())
                        : dialect.quoteIdentifier(elemColRel.fkColumn());
                    String rootTable = dialect.quoteIdentifier(ctx.entity().tableName());
                    String ownerIdColumn = dialect.quoteIdentifier(ctx.entity().idColumn());
                    String aggFunc = sort.ascending() ? "MIN" : "MAX";

                    if (hasToManySort && toManyPropForHydration != null && firstSegment.equals(toManyPropForHydration)) {
                        // Hydrating with LEFT JOIN: use alias for ORDER BY, full expression for WHERE
                        orderByTerm = dialect.quoteIdentifier("agg_" + aggIndex);
                        seekTerm = aggFunc + "(" + joinAlias + "." + dialect.quoteIdentifier(colPart) + ")";
                        aggIndex++;
                    } else {
                        // No hydration: use correlated subquery for both
                        String subquery = "(SELECT " + aggFunc + "(" + childTable + "." + dialect.quoteIdentifier(colPart)
                                        + ") FROM " + childTable + " WHERE " + childTable + "." + fkColumn
                                        + " = " + rootTable + "." + ownerIdColumn + ")";
                        orderByTerm = subquery;
                        seekTerm = subquery;
                    }
                } else {
                    // Regular nested path - use qualified column
                    String qualifiedCol = dialect.quoteIdentifier(prop.replace('.', '_'));
                    orderByTerm = qualifiedCol;
                    seekTerm = qualifiedCol;
                }
            } else {
                // Simple property
                orderByTerm = dialect.quoteIdentifier(prop);
                seekTerm = orderByTerm;
            }

            orderByTerms.add(orderByTerm);
            seekTerms.add(seekTerm);
        }

        // Add id term if we added it for uniqueness
        if (hasToManySort && sorts.size() < properties.size()) {
            // Qualify id with root table to avoid ambiguity with JOINed tables
            String qualifiedId = dialect.quoteIdentifier(ctx.entity().tableName()) + "." + dialect.quoteIdentifier(ctx.entity().idColumn());
            orderByTerms.add(qualifiedId);
            seekTerms.add(qualifiedId);
        }

        // Build ORDER BY clause using aliases
        String orderByClause = "";
        if (!orderByTerms.isEmpty()) {
            StringBuilder ob = new StringBuilder(" ORDER BY ");
            for (int i = 0; i < orderByTerms.size(); i++) {
                if (i > 0) ob.append(", ");
                ob.append(orderByTerms.get(i)).append(ascendingList.get(i) ? " ASC" : " DESC");
            }
            orderByClause = ob.toString();
        }

        // Build seek predicate - wrap in subquery if we have aggregate expressions
        String seekStmt;
        if (!seekTerms.isEmpty()) {
            // Check if any seek term contains an aggregate function
            boolean hasAggregatesInSeek = seekTerms.stream()
                .anyMatch(term -> term.contains("MIN(") || term.contains("MAX("));

            String seekPredicate = sqlBuilder.seekClause(seekTerms, ascendingList, true);
            String whereOrAnd = shellHasWhere ? " AND " : " WHERE ";

            if (hasAggregatesInSeek && hasToManySort && toManyPropForHydration != null) {
                // Wrap in subquery to allow WHERE on aggregate aliases
                // Inner query produces the aggregates with aliases
                // Outer query applies WHERE clause on those aliases
                String innerAlias = "inner_";
                StringBuilder wrappedSql = new StringBuilder();
                wrappedSql.append("SELECT ").append(innerAlias).append(".* FROM (");
                wrappedSql.append(baseSql);
                wrappedSql.append(") AS ").append(innerAlias);
                wrappedSql.append(whereOrAnd);
                wrappedSql.append("(:cursor0 IS NULL OR ");

                // Replace aggregate expressions and qualified columns with inner alias references
                String aliasedSeekPredicate = seekPredicate;
                int replaceAggIndex = 0;
                for (int i = 0; i < sorts.size(); i++) {
                    SortSpec sort = sorts.get(i);
                    String prop = sort.property();
                    if (prop.contains(".")) {
                        String firstSegment = prop.substring(0, prop.indexOf('.'));
                        if (firstSegment.equals(toManyPropForHydration)) {
                            String colPart = prop.substring(prop.indexOf('.') + 1).replace('.', '_');
                            String aggFunc = sort.ascending() ? "MIN" : "MAX";
                            String fullExpr = aggFunc + "(" + joinAlias + "." + dialect.quoteIdentifier(colPart) + ")";
                            String alias = dialect.quoteIdentifier("agg_" + replaceAggIndex);
                            aliasedSeekPredicate = aliasedSeekPredicate.replace(fullExpr, innerAlias + "." + alias);
                            replaceAggIndex++;
                        }
                    }
                }

                // Also replace qualified table.column references with inner alias
                String rootTableQuoted = dialect.quoteIdentifier(ctx.entity().tableName());
                aliasedSeekPredicate = aliasedSeekPredicate.replace(rootTableQuoted + ".", innerAlias + ".");

                wrappedSql.append(aliasedSeekPredicate);
                wrappedSql.append(")");

                // Update ORDER BY to use inner alias for all columns
                String aliasedOrderBy = orderByClause.replace("\"agg_", innerAlias + ".\"agg_");
                aliasedOrderBy = aliasedOrderBy.replace(rootTableQuoted + ".", innerAlias + ".");
                wrappedSql.append(aliasedOrderBy);

                seekStmt = wrappedSql.toString();
            } else {
                seekStmt = baseSql + whereOrAnd
                        + "(:cursor0 IS NULL OR " + seekPredicate + ")" + orderByClause;
            }
        } else {
            seekStmt = baseSql + orderByClause;
        }

        // Register the seek SQL constant
        String seekConstBase = toSqlConstantName("CURSOR_" + method.name());
        String seekConstName = ctx.registerConstant(seekConstBase, seekStmt);

        // Also register a count SQL for total (optional, used if requestTotal)
        String countSql = buildCountSql(ctx, method);
        String countConstBase = toSqlConstantName("COUNT_CURSOR_" + method.name());
        String countConstName = ctx.registerConstant(countConstBase, countSql);

        List<ParamBinding> bindings = method.paramBindings();
        String pageRequestParam = findPageRequestParam(method);

        CodeBlock.Builder body = CodeBlock.builder();

        // Build the SQL: seek statement + LIMIT
        // String sql = SEEK_SQL + " LIMIT :limit";
        body.addStatement("$T sql = $L + $S", String.class, seekConstName, dialect.limitClause(":limit"));

        // var content = jdbcClient.sql(sql).param("limit", pageRequest.size());
        body.add("var $L = jdbcClient\n", "content");
        body.add("    .sql(sql)\n");
        body.add("    .param($S, $L.size())\n", "limit", pageRequestParam);

        // Bind cursor keys if cursor is present
        if (!seekTerms.isEmpty()) {
            body.add("    .param($S, $L.cursor().isPresent() ? $L.cursor().get().get(0) : null)\n",
                    "cursor0", pageRequestParam, pageRequestParam);
            // For multi-key cursors, add more bindings
            for (int i = 1; i < seekTerms.size(); i++) {
                body.add("    .param($S, $L.cursor().isPresent() ? $L.cursor().get().get($L) : null)\n",
                        "cursor" + i, pageRequestParam, pageRequestParam, i);
            }
        }

        // Bind predicate parameters
        appendParamBindings(body, bindings, method);

        // .query(new EntityRowMapper()).list();
        body.add("    .query(new $T())\n", rowMapperType);
        body.addStatement("    .list()");

        // Hydrate TO_MANY relations for cursor extraction if needed
        if (hasToManySort && toManyPropForHydration != null) {
            body.addStatement("$L($L)", "hydrateList", "content");
        }

        // Build cursors for each result row
        // List<PageRequest.Cursor> cursors = new ArrayList<>();
        body.addStatement("$T<$T> $L = new $T<>()",
                List.class, CURSOR, "cursors", ArrayList.class);

        // for (Entity row : content) { cursors.add(CursorKeys.forEntity(props, ascending, access, row)); }
        if (!properties.isEmpty()) {
            // Build the property list (includes appended id for TO_MANY sorts)
            body.add("$T<$T> $L = $T.asList(",
                    List.class, String.class, "sortPaths", java.util.Arrays.class);
            for (int i = 0; i < properties.size(); i++) {
                if (i > 0) body.add(", ");
                body.add("$S", properties.get(i));
            }
            body.add(");\n");

            // Build the ascending list
            body.add("$T<$T> $L = $T.asList(",
                    List.class, Boolean.class, "sortDirs", java.util.Arrays.class);
            for (int i = 0; i < ascendingList.size(); i++) {
                if (i > 0) body.add(", ");
                body.add("$L", ascendingList.get(i));
            }
            body.add(");\n");

            // Build a simple ValueAccess using a switch on path
            body.add("$T<$T> $L = (path, entity) -> {\n", VALUE_ACCESS, entityType, "access");
            body.add("    return switch (path) {\n");
            for (String prop : properties) {
                // Find the corresponding sort spec if it exists
                SortSpec matchingSort = null;
                for (SortSpec s : sorts) {
                    if (s.property().equals(prop)) {
                        matchingSort = s;
                        break;
                    }
                }

                String getter = "get" + Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
                // Handle dot paths by chaining getters
                if (prop.contains(".")) {
                    String[] parts = prop.split("\\.");
                    // Generate runtime type check for to-many paths
                    String firstGetter = "get" + Character.toUpperCase(parts[0].charAt(0)) + parts[0].substring(1);
                    // Resolve the element type for the collection (if to-many)
                    String elementTypeName = resolveCollectionElementType(ctx, parts[0]);

                    // Check if this is an ENTITY_COLLECTION path that needs aggregate extraction
                    boolean isToManyPath = false;
                    for (TypeDefinition.Prop p : ctx.entity().properties()) {
                        if ((p instanceof TypeDefinition.Prop.EntityCollection || p instanceof TypeDefinition.Prop.ElementCollection)
                                && p.name().equals(parts[0])) {
                            isToManyPath = true;
                            break;
                        }
                    }

                    body.add("        case $S -> {\n", prop);
                    body.add("            var $L = entity.$L();\n", "v0", firstGetter);
                    body.add("            if ($L instanceof $T) {\n", "v0", java.util.List.class);

                    // For cursor pagination with TO_MANY sorts, we need to extract MIN/MAX value
                    // to match the SQL aggregate function used in ORDER BY
                    if (isToManyPath && matchingSort != null) {
                        String itemVar = parts[0] + "i";
                        if (elementTypeName != null) {
                            StringBuilder chain = new StringBuilder("((" + ClassName.bestGuess(elementTypeName) + ") " + itemVar + ")");
                            for (int i = 1; i < parts.length; i++) {
                                String g = "get" + Character.toUpperCase(parts[i].charAt(0)) + parts[i].substring(1);
                                chain.append(".").append(g).append("()");
                            }
                            body.add("                var $L = (($T<?>) $L).stream().map($L -> $L).toList();\n",
                                    "values", java.util.List.class, "v0", itemVar, chain.toString());
                            // Compute MIN/MAX to match SQL aggregate
                            String aggCall = matchingSort.ascending()
                                ? "$L.stream().min(java.util.Comparator.naturalOrder()).orElse(null)"
                                : "$L.stream().max(java.util.Comparator.naturalOrder()).orElse(null)";
                            body.add("                yield $L.isEmpty() ? null : " + aggCall + ";\n",
                                    "values", "values");
                        } else {
                            body.add("                yield null;\n");
                        }
                    } else {
                        // To-many: stream().map().toList()
                        String itemVar = parts[0] + "i";
                        if (elementTypeName != null) {
                            StringBuilder chain = new StringBuilder("((" + ClassName.bestGuess(elementTypeName) + ") " + itemVar + ")");
                            for (int i = 1; i < parts.length; i++) {
                                String g = "get" + Character.toUpperCase(parts[i].charAt(0)) + parts[i].substring(1);
                                chain.append(".").append(g).append("()");
                            }
                            body.add("                yield (($T<?>) $L).stream().map($L -> $L).toList();\n",
                                    java.util.List.class, "v0", itemVar, chain.toString());
                        } else {
                            // Fallback: use entity type as cast definition
                            body.add("                yield (($T<?>) $L).stream().map($L -> (($T) $L).$L()).toList();\n",
                                    java.util.List.class, "v0", itemVar,
                                    ClassName.bestGuess(ctx.entity().typeName()), itemVar,
                                    "get" + Character.toUpperCase(parts[parts.length - 1].charAt(0)) + parts[parts.length - 1].substring(1));
                        }
                    }
                    body.add("            } else {\n");
                    // To-one: this branch handles non-collection intermediate values
                    // For dot paths starting from a collection, this is unreachable
                    body.add("                yield null;\n");
                    body.add("            }\n");
                    body.add("        }\n");
                } else {
                    body.add("        case $S -> entity.$L();\n", prop, getter);
                }
            }
            body.add("        default -> throw new $T($S + path);\n",
                    IllegalArgumentException.class, "Unknown path: ");
            body.add("    };\n");
            body.addStatement("};");

            body.addStatement("for ($T row : content) {\n" +
                    "    cursors.add($T.forEntity(sortPaths, sortDirs, access, row));\n" +
                    "}", entityType, CURSOR_KEYS);
        }

        // Compute total if requested
        body.add("long $L = $L.requestTotal() ? jdbcClient.sql($L)", "total", pageRequestParam, countConstName);
        appendParamBindings(body, bindings, method);
        body.add(".query($T.class).single() : 0", Long.class);
        body.addStatement("");

        // return new SimpleCursoredPage<>(pageRequest, content, cursors, total);
        body.addStatement("return new $T<>($L, $L, $L, $L)",
                SIMPLE_CURSORED_PAGE, pageRequestParam, "content", "cursors", "total");

        builder.addCode(body.build());
        return builder.build();
    }

    private String buildSortTerms(List<SortSpec> sorts) {
        List<String> terms = new ArrayList<>();
        for (SortSpec sort : sorts) {
            String col = dialect.quoteIdentifier(sort.property().replace('.', '_'));
            terms.add(col + (sort.ascending() ? " ASC" : " DESC"));
        }
        return String.join(", ", terms);
    }

    private boolean isToManyPath(RepositoryContext ctx, String firstSegment) {
        for (TypeDefinition.Prop prop : ctx.entity().properties()) {
            if ((prop instanceof TypeDefinition.Prop.EntityCollection
                    || prop instanceof TypeDefinition.Prop.ElementCollection)
                    && prop.name().equals(firstSegment)) {
                return true;
            }
        }
        return false;
    }

    private String resolveCollectionElementType(RepositoryContext ctx, String firstSegment) {
        for (TypeDefinition.Prop prop : ctx.entity().properties()) {
            if (prop instanceof TypeDefinition.Prop.EntityCollection ec
                    && prop.name().equals(firstSegment)) {
                return ec.target() != null ? ec.target().typeName() : ec.javaType();
            } else if (prop instanceof TypeDefinition.Prop.ElementCollection elemCol
                    && prop.name().equals(firstSegment)) {
                return elemCol.elementType();
            }
        }
        return null;
    }

    // ── Count ────────────────────────────────────────────────────────────

    private MethodSpec emitCount(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));

        String sql = buildCountSql(ctx, method);
        String constBase = toSqlConstantName(method.name());
        String constName = ctx.registerConstant(constBase, sql);

        List<ParamBinding> bindings = method.paramBindings();

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("return jdbcClient\n");
        body.add("    .sql($L)\n", constName);
        appendParamBindings(body, bindings, method);
        body.add("    .query($T.class).single();", Long.class);
        builder.addCode(body.build());

        return builder.build();
    }

    // ── Exists ───────────────────────────────────────────────────────────

    private MethodSpec emitExists(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));

        String sql = buildExistsSql(ctx, method);
        String constBase = toSqlConstantName(method.name());
        String constName = ctx.registerConstant(constBase, sql);

        List<ParamBinding> bindings = method.paramBindings();

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("return jdbcClient\n");
        body.add("    .sql($L)\n", constName);
        appendParamBindings(body, bindings, method);
        body.add("    .query($T.class).single();", Boolean.class);
        builder.addCode(body.build());

        return builder.build();
    }

    private String buildExistsSql(RepositoryContext ctx, MethodContext method) {
        SqlBuilder sqlBuilder = new SqlBuilder(ctx.entity());
        sqlBuilder.dialect(dialect);
        return sqlBuilder.render(new QueryDefinition.Exists(method.predicates(), List.of()));
    }

    // ── DeleteBy ─────────────────────────────────────────────────────────

    private MethodSpec emitDeleteBy(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));

        SqlBuilder sqlBuilder = new SqlBuilder(ctx.entity());
        sqlBuilder.dialect(dialect);
        ResultKind rk = method.resultKind() == ResultKind.VOID ? ResultKind.VOID : method.resultKind();
        String sql = sqlBuilder.render(new QueryDefinition.DeleteWhere(method.predicates(), List.of(), rk));
        String constBase = toSqlConstantName(method.name());
        String constName = ctx.registerConstant(constBase, sql);

        List<ParamBinding> bindings = method.paramBindings();

        CodeBlock.Builder body = CodeBlock.builder();
        boolean isVoid = method.resultKind() == ResultKind.VOID;
        if (!isVoid) body.add("return ");
        body.add("jdbcClient\n");
        body.add("    .sql($L)\n", constName);
        appendParamBindings(body, bindings, method);
        body.add("    .update();");
        builder.addCode(body.build());

        return builder.build();
    }

    // ── Custom @Query methods ────────────────────────────────────────────

    /**
     * Generates a method override for a {@code @Query}-annotated method.
     * Uses the raw SQL from the annotation, binds parameters by name,
     * and handles the return type (SINGLE, LIST, OPTIONAL, etc.).
     */
    private MethodSpec emitCustomQuery(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));

        String sql = method.customSql();
        String constBase = toSqlConstantName(method.name());
        String constName = ctx.registerConstant(constBase, sql);

        List<ParamBinding> bindings = method.paramBindings();
        ClassName rowMapperType = ctx.rowMapperType();
        Operation op = method.operation();

        CodeBlock.Builder body = CodeBlock.builder();

        if (op == Operation.DELETE) {
            // DELETE query: jdbcClient.sql(SQL).param(...).update()
            boolean isVoid = method.resultKind() == ResultKind.VOID;
            if (!isVoid) body.add("return ");
            body.add("jdbcClient\n");
            body.add("    .sql($L)\n", constName);
            appendParamBindings(body, bindings, method);
            body.add("    .update();");
        } else {
            // SELECT query: jdbcClient.sql(SQL).param(...).query(new RowMapper()).terminal()
            body.add("return jdbcClient\n");
            body.add("    .sql($L)\n", constName);
            appendParamBindings(body, bindings, method);
            body.add("    .query(new $T())\n", rowMapperType);
            emitTerminal(body, method.resultKind(), ctx);
            body.add(";");
        }

        builder.addCode(body.build());
        return builder.build();
    }

    // ── Shared helpers ───────────────────────────────────────────────────

    private String findPageRequestParam(MethodContext method) {
        for (VariableElement param : method.parameters()) {
            String fqn = param.asType().toString();
            if (fqn.contains("PageRequest")) {
                return param.getSimpleName().toString();
            }
        }
        return "pageRequest";
    }

    private void appendParamBindings(CodeBlock.Builder body, List<ParamBinding> bindings,
                                      MethodContext method) {
        for (ParamBinding binding : bindings) {
            VariableElement param = method.parameters().get(binding.argIndex());
            String paramName = param.getSimpleName().toString();
            LikePattern pattern = binding.pattern();

            if (pattern.wildcarded()) {
                String prefix = pattern.prefix();
                String suffix = pattern.suffix();
                if (!prefix.isEmpty() && !suffix.isEmpty()) {
                    body.add("    .param($S, $S + $L + $S)\n", binding.paramName(), "%", paramName, "%");
                } else if (!prefix.isEmpty()) {
                    body.add("    .param($S, $S + $L)\n", binding.paramName(), "%", paramName);
                } else {
                    body.add("    .param($S, $L + $S)\n", binding.paramName(), paramName, "%");
                }
            } else {
                body.add("    .param($S, $L)\n", binding.paramName(), paramName);
            }
        }
    }

    /**
     * Appends the terminal operation call to the code block.
     */
    private void emitTerminal(CodeBlock.Builder body, ResultKind resultKind, RepositoryContext ctx) {
        switch (resultKind) {
            case SINGLE -> body.add(".single()");
            case OPTIONAL -> body.add(".optional()");
            case LIST -> body.add(".list()");
            case ARRAY -> {
                String entityFqn = ctx.entity().typeName();
                ClassName entityType = ClassName.bestGuess(entityFqn);
                body.add(".list().toArray(new $T[0])", entityType);
            }
            case STREAM -> body.add(".list().stream()");
            case SET -> body.add(".list().stream().collect($T.toSet())",
                    ClassName.get("java.util.stream", "Collectors"));
            default -> body.add(".list()");
        }
    }

    /**
     * Renders an operator expression, matching QueryCompiler's rendering.
     */
    private String renderOperator(io.github.fludakit.data.query.Operator operator,
                                   String column, String param, boolean ignoreCase) {
        String col = ignoreCase ? "LOWER(" + column + ")" : column;
        String val = ignoreCase ? "LOWER(:" + param + ")" : ":" + param;
        return switch (operator) {
            case EQ, IGNORE_CASE_EQ -> col + " = " + val;
            case NOT_EQ -> col + " <> " + val;
            case GREATER_THAN -> col + " > " + val;
            case GREATER_THAN_EQUAL -> col + " >= " + val;
            case LESS_THAN -> col + " < " + val;
            case LESS_THAN_EQUAL -> col + " <= " + val;
            case LIKE -> col + " LIKE " + val;
            case NOT_LIKE -> col + " NOT LIKE " + val;
            case STARTS_WITH -> col + " LIKE " + val;
            case ENDS_WITH -> col + " LIKE " + val;
            case CONTAINS -> col + " LIKE " + val;
            case NOT_STARTS_WITH -> col + " NOT LIKE " + val;
            case NOT_ENDS_WITH -> col + " NOT LIKE " + val;
            case NOT_CONTAINS -> col + " NOT LIKE " + val;
            case BETWEEN -> col + " BETWEEN :" + param + "_from AND :" + param + "_to";
            case NOT_BETWEEN -> col + " NOT BETWEEN :" + param + "_from AND :" + param + "_to";
            case IN -> col + " IN (:" + param + ")";
            case NOT_IN -> col + " NOT IN (:" + param + ")";
            case IS_NULL -> col + " IS NULL";
            case IS_NOT_NULL -> col + " IS NOT NULL";
            case TRUE -> col + " = TRUE";
            case FALSE -> col + " = FALSE";
        };
    }

    // ── Hydration helpers ────────────────────────────────────────────────

    /**
     * Returns whether the entity has to-many collections that need hydration.
     */
    private boolean hasToManyCollections(io.github.fludakit.data.metadata.TypeDefinition entity) {
        for (TypeDefinition.Prop prop : entity.properties()) {
            if (prop instanceof TypeDefinition.Prop.EntityCollection
                    || prop instanceof TypeDefinition.Prop.ElementCollection) {
                return true;
            }
        }
        return false;
    }

    /**
     * Emits a terminal operation that captures the result in a variable (no return).
     */
    private void emitTerminalSingle(CodeBlock.Builder body, ResultKind resultKind, RepositoryContext ctx) {
        switch (resultKind) {
            case SINGLE -> body.add(".single()");
            case OPTIONAL -> body.add(".optional()");
            case LIST -> body.add(".list()");
            case ARRAY -> {
                String entityFqn = ctx.entity().typeName();
                ClassName entityType = ClassName.bestGuess(entityFqn);
                body.add(".list().toArray(new $T[0])", entityType);
            }
            case STREAM -> body.add(".list().stream()");
            case SET -> body.add(".list().stream().collect($T.toSet())",
                    ClassName.get("java.util.stream", "Collectors"));
            default -> body.add(".list()");
        }
    }

    /**
     * Emits the hydration call appropriate for the result kind, but only if the entity has collections to hydrate.
     */
    private void emitHydrationCall(CodeBlock.Builder body, ResultKind resultKind, RepositoryContext ctx) {
        HydrationEmitter hydrationEmitter = new HydrationEmitter(dialect);
        if (!hydrationEmitter.hasCollectionsToHydrate(ctx.entity())) {
            return; // No hydration needed
        }
        
        switch (resultKind) {
            case SINGLE -> body.add("hydrate(result);\n");
            case OPTIONAL -> body.add("result.ifPresent(this::hydrate);\n");
            case LIST, SET, ARRAY, STREAM -> body.add("hydrateList(result);\n");
            default -> { }
        }
    }

    // ── Naming utilities ─────────────────────────────────────────────────

    /**
     * Converts a method name to a SQL constant name base.
     * E.g. {@code findByName} → {@code FIND_BY_NAME_SQL}.
     */
    static String toSqlConstantName(String methodName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < methodName.length(); i++) {
            char c = methodName.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                char prev = methodName.charAt(i - 1);
                if (Character.isLowerCase(prev)) {
                    sb.append('_');
                } else if (i + 1 < methodName.length()
                        && Character.isLowerCase(methodName.charAt(i + 1))) {
                    sb.append('_');
                }
            }
            sb.append(Character.toUpperCase(c));
        }
        sb.append("_SQL");
        return sb.toString();
    }

    /**
     * Returns the Stream API method name for aggregate extraction.
     * @param ascending true for MIN (min), false for MAX (max)
     */
    private static String aggMethodName(boolean ascending) {
        return ascending ? "min" : "max";
    }
}
