package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.processor.support.MethodOverrides;
import io.github.fludakit.data.query.LikePattern;
import io.github.fludakit.data.query.LimitSpec;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.Predicate;
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
                body.add("hydrateList($L);\n", "result");
                body.add("return $L.stream();", "result");
            } else {
                body.add("var $L = jdbcClient\n", "result");
                body.add("    .sql($L)\n", sqlConstantName);
                appendParamBindings(body, bindings, method);
                body.add("    .query(new $T())\n", rowMapperType);
                emitTerminalSingle(body, method.resultKind(), ctx);
                body.add(";\n");
                emitHydrationCall(body, method.resultKind());
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

        // Build the base SELECT SQL shell (no ORDER BY, no seek clause, no LIMIT).
        // Pass empty sorts so the render produces no ORDER BY — the ORDER BY below is the sole source.
        SqlBuilder sqlBuilder = new SqlBuilder(ctx.entity());
        sqlBuilder.dialect(dialect);
        String baseSql = sqlBuilder.render(new QueryDefinition.Read(
                ResultKind.LIST, method.predicates(), List.of(),
                PagingMode.NONE, LimitSpec.none(), List.of(), List.of(), method.distinct()));
        boolean shellHasWhere = !method.predicates().isEmpty();

        // Build sort terms and ascending flags for seek clause
        List<String> terms = new ArrayList<>();
        List<Boolean> ascending = new ArrayList<>();
        for (SortSpec sort : sorts) {
            String col = dialect.quoteIdentifier(sort.property().replace('.', '_'));
            terms.add(col);
            ascending.add(sort.ascending());
        }

        // Build ORDER BY clause
        String orderByClause = "";
        if (!sorts.isEmpty()) {
            orderByClause = " ORDER BY " + buildSortTerms(sorts);
        }

        // Build the seek statement (base + conditional seek clause + ORDER BY).
        // The conditional seek (:cursor0 IS NULL OR <predicate>) handles both the first page
        // (no cursor — cursor keys are null) and subsequent pages without branching SQL.
        String seekStmt;
        if (!terms.isEmpty()) {
            String seekPredicate = sqlBuilder.seekClause(terms, ascending, true);
            seekStmt = baseSql + (shellHasWhere ? " AND " : " WHERE ")
                    + "(:cursor0 IS NULL OR " + seekPredicate + ")" + orderByClause;
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
        if (!terms.isEmpty()) {
            body.add("    .param($S, $L.cursor().isPresent() ? $L.cursor().get().get(0) : null)\n",
                    "cursor0", pageRequestParam, pageRequestParam);
            // For multi-key cursors, add more bindings
            for (int i = 1; i < terms.size(); i++) {
                body.add("    .param($S, $L.cursor().isPresent() ? $L.cursor().get().get($L) : null)\n",
                        "cursor" + i, pageRequestParam, pageRequestParam, i);
            }
        }

        // Bind predicate parameters
        appendParamBindings(body, bindings, method);

        // .query(new EntityRowMapper()).list();
        body.add("    .query(new $T())\n", rowMapperType);
        body.addStatement("    .list()");

        // Build cursors for each result row
        // List<PageRequest.Cursor> cursors = new ArrayList<>();
        body.addStatement("$T<$T> $L = new $T<>()",
                List.class, CURSOR, "cursors", ArrayList.class);

        // for (Entity row : content) { cursors.add(CursorKeys.forEntity(props, ascending, access, row)); }
        if (!sorts.isEmpty()) {
            // Build the property list
            body.add("$T<$T> $L = $T.asList(",
                    List.class, String.class, "sortPaths", java.util.Arrays.class);
            for (int i = 0; i < sorts.size(); i++) {
                if (i > 0) body.add(", ");
                body.add("$S", sorts.get(i).property());
            }
            body.add(");\n");

            // Build the ascending list
            body.add("$T<$T> $L = $T.asList(",
                    List.class, Boolean.class, "sortDirs", java.util.Arrays.class);
            for (int i = 0; i < sorts.size(); i++) {
                if (i > 0) body.add(", ");
                body.add("$L", sorts.get(i).ascending());
            }
            body.add(");\n");

            // Build a simple ValueAccess using a switch on path
            body.add("$T<$T> $L = (path, entity) -> {\n", VALUE_ACCESS, entityType, "access");
            body.add("    return switch (path) {\n");
            for (SortSpec sort : sorts) {
                String prop = sort.property();
                String getter = "get" + Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
                // Handle dot paths by chaining getters
                if (prop.contains(".")) {
                    String[] parts = prop.split("\\.");
                    // Generate runtime type check for to-many paths
                    String firstGetter = "get" + Character.toUpperCase(parts[0].charAt(0)) + parts[0].substring(1);
                    // Resolve the element type for the collection (if to-many)
                    String elementTypeName = resolveCollectionElementType(ctx, parts[0]);

                    body.add("        case $S -> {\n", prop);
                    body.add("            var $L = entity.$L();\n", "v0", firstGetter);
                    body.add("            if ($L instanceof $T) {\n", "v0", java.util.List.class);
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
                        // Fallback: use entity type as cast target
                        body.add("                yield (($T<?>) $L).stream().map($L -> (($T) $L).$L()).toList();\n",
                                java.util.List.class, "v0", itemVar,
                                ClassName.bestGuess(ctx.entity().typeName()), itemVar,
                                "get" + Character.toUpperCase(parts[parts.length - 1].charAt(0)) + parts[parts.length - 1].substring(1));
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
        for (io.github.fludakit.data.metadata.TypeMapping.Prop prop : ctx.entity().props()) {
            if (prop instanceof io.github.fludakit.data.metadata.TypeMapping.Prop.EntityCollection ec
                    && ec.name().equals(firstSegment)) {
                return true;
            }
            if (prop instanceof io.github.fludakit.data.metadata.TypeMapping.Prop.ElementCollection ec
                    && ec.name().equals(firstSegment)) {
                return true;
            }
        }
        return false;
    }

    private String resolveCollectionElementType(RepositoryContext ctx, String firstSegment) {
        for (io.github.fludakit.data.metadata.TypeMapping.Prop prop : ctx.entity().props()) {
            if (prop instanceof io.github.fludakit.data.metadata.TypeMapping.Prop.EntityCollection ec
                    && ec.name().equals(firstSegment)) {
                return ec.targetTypeName();
            }
            if (prop instanceof io.github.fludakit.data.metadata.TypeMapping.Prop.ElementCollection ec
                    && ec.name().equals(firstSegment) && ec.target() != null) {
                return ec.target().typeName();
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
    private boolean hasToManyCollections(io.github.fludakit.data.metadata.TypeMapping<?> entity) {
        for (io.github.fludakit.data.metadata.TypeMapping.Prop prop : entity.props()) {
            if (prop instanceof io.github.fludakit.data.metadata.TypeMapping.Prop.EntityCollection
                    || prop instanceof io.github.fludakit.data.metadata.TypeMapping.Prop.ElementCollection) {
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
     * Emits the hydration call appropriate for the result kind.
     */
    private void emitHydrationCall(CodeBlock.Builder body, ResultKind resultKind) {
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
}
