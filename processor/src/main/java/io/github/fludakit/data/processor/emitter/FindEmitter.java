package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.JoinEdge;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.processor.context.MethodContext.Operation;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.processor.support.ExceptionTranslation;
import io.github.fludakit.data.processor.support.MethodOverrides;
import io.github.fludakit.data.query.Order;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;
import io.github.fludakit.data.query.Window;
import io.github.fludakit.data.sql.SqlBuilder;
import io.github.fludakit.data.sql.SqlDialect;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates method implementations for finder, count, exists, and {@code @Query} methods.
 *
 * <p>Dispatches on {@link MethodContext#query()} ({@link QueryDefinition}):</p>
 * <ul>
 *   <li>{@link QueryDefinition.Read} + {@link Window.None}/{@link Window.Limit} &rarr; plain finder</li>
 *   <li>{@link QueryDefinition.Read} + {@link Window.Offset} &rarr; offset-paged finder</li>
 *   <li>{@link QueryDefinition.Read} + {@link Window.Cursor} &rarr; cursor-paged finder</li>
 *   <li>{@link QueryDefinition.Count} / {@link QueryDefinition.Exists}</li>
 *   <li>{@link QueryDefinition.Native} &rarr; custom {@code @Query}</li>
 * </ul>
 */
final class FindEmitter {

    private static final ClassName SIMPLE_PAGE = ClassName.get("io.github.fludakit.data.page", "SimplePage");
    private static final ClassName SIMPLE_CURSORED_PAGE = ClassName.get("io.github.fludakit.data.page", "SimpleCursoredPage");
    private static final ClassName CURSOR = ClassName.get("jakarta.data.page", "PageRequest", "Cursor");
    private static final ClassName CURSOR_KEYS = ClassName.get("io.github.fludakit.data.sql", "CursorKeys");
    private static final ClassName VALUE_ACCESS = ClassName.get("io.github.fludakit.data.metadata", "ValueReader");

    private final SqlDialect dialect;
    private final Types types;
    private final DeclaredType baseRepositoryType;
    private final DeclaredType repositoryOwnType;

    private final SelectSql selectSql;
    private final OffsetPageEmitter offsetPages;
    private final CursorPageEmitter cursorPages;

    FindEmitter(SqlDialect dialect, Types types, DeclaredType baseRepositoryType, DeclaredType repositoryOwnType) {
        this.dialect = dialect;
        this.types = types;
        this.baseRepositoryType = baseRepositoryType;
        this.repositoryOwnType = repositoryOwnType;
        this.selectSql = new SelectSql(dialect);
        this.offsetPages = new OffsetPageEmitter(dialect, selectSql);
        this.cursorPages = new CursorPageEmitter(dialect, selectSql);
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

    /** The override builder for {@code method}, resolved against the repository type that declares it. */
    private MethodSpec.Builder overrideFor(RepositoryContext ctx, MethodContext method) {
        return MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));
    }

    /**
     * Generates a method override for a finder, count, exists, deleteBy, or @Query method.
     *
     * @return the method spec, or {@code null} if the method is not handled by this emitter
     */
    MethodSpec emit(RepositoryContext ctx, MethodContext method) {
        return switch (method.query()) {
            case QueryDefinition.Native ignored -> emitCustomQuery(ctx, method);
            case QueryDefinition.Count ignored -> emitCount(ctx, method);
            case QueryDefinition.Exists ignored -> emitExists(ctx, method);
            case QueryDefinition.Read read -> switch (read.window()) {
                case Window.Offset ignored -> offsetPages.emitOffsetPagedFinder(overrideFor(ctx, method), ctx, method);
                case Window.Cursor ignored -> cursorPages.emitCursorPagedFinder(overrideFor(ctx, method), ctx, method);
                case Window.None ignored -> emitPlainFinder(ctx, method);
                case Window.Limit ignored -> emitPlainFinder(ctx, method);
            };
            default -> null;
        };
    }

    /**
     * Select shell from the method's {@link QueryDefinition.Read}. A static {@code First<n>} cap
     * ({@link Window.None}) stays on the shell, so the compiler renders it after the {@code ORDER BY}; a
     * parameter window is not part of the shell (paged limit/seek are applied later).
     */
    private static QueryDefinition.Read selectShell(MethodContext method) {
        QueryDefinition.Read read = requireRead(method);
        ResultKind rk = switch (read.resultKind()) {
            case COUNT, EXISTS -> ResultKind.LIST;
            default -> read.resultKind();
        };
        Window window = read.window() instanceof Window.None ? read.window() : Window.None.unpaged();
        return new QueryDefinition.Read(
                rk, read.predicates(), read.paramBindings(), read.distinct(),
                read.order(), window);
    }

    /** Select shell with order stripped — {@code ORDER BY} is spliced at runtime. */
    private static QueryDefinition.Read selectShellUnordered(MethodContext method) {
        QueryDefinition.Read shell = selectShell(method);
        return new QueryDefinition.Read(
                shell.resultKind(), shell.predicates(), shell.paramBindings(), shell.distinct(),
                Order.None.INSTANCE, Window.None.unpaged());
    }

    private static QueryDefinition.Read requireRead(MethodContext method) {
        if (method.query() instanceof QueryDefinition.Read read) {
            return read;
        }
        throw new IllegalStateException("Expected QueryDefinition.Read for " + method.name()
                + ", got " + method.query().getClass().getSimpleName());
    }

    private static QueryDefinition.Count countDefinition(MethodContext method) {
        return switch (method.query()) {
            case QueryDefinition.Count c -> c;
            case QueryDefinition.Read r -> new QueryDefinition.Count(r.predicates(), r.paramBindings(), r.distinct());
            case QueryDefinition.Criteria c -> new QueryDefinition.Count(c.predicates(), c.paramBindings(), false);
            default -> throw new IllegalStateException("Cannot derive COUNT from " + method.query());
        };
    }

    private static QueryDefinition.Exists existsDefinition(MethodContext method) {
        return switch (method.query()) {
            case QueryDefinition.Exists e -> e;
            case QueryDefinition.Criteria c -> new QueryDefinition.Exists(c.predicates(), c.paramBindings());
            default -> throw new IllegalStateException("Cannot derive EXISTS from " + method.query());
        };
    }

    // ── Plain finder ─────────────────────────────────────────────────────

    private MethodSpec emitPlainFinder(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = overrideFor(ctx, method);

        String sql = selectSql.buildSelectSql(ctx, method);
        String constBase = method.sqlConstantBase();
        String constName = ctx.registerConstant(constBase, sql);

        addPlainFinderBody(builder, ctx, method, constName);
        return builder.build();
    }

    private void addPlainFinderBody(MethodSpec.Builder builder, RepositoryContext ctx,
                                     MethodContext method, String sqlConstantName) {
        ClassName rowMapperType = ctx.rowMapperType();
        boolean needsHydration = hasToManyCollections(ctx.entity());

        CodeBlock.Builder body = CodeBlock.builder();

        if (needsHydration) {
            // For aggregate roots, capture result in a variable and hydrate before returning
            if (method.resultKind() == ResultKind.STREAM) {
                // Stream: get list first, hydrate, then convert to stream
                body.add("var $L = jdbcClient\n", "result");
                body.add("    .sql($L)\n", sqlConstantName);
                ParamBindingCode.append(body, method);
                body.add("    .query(new $T())\n", rowMapperType);
                body.add("    .list();\n");
                emitHydrationCall(dialect, body, ResultKind.LIST, ctx);
                body.add("return $L.stream();", "result");
            } else {
                body.add("var $L = jdbcClient\n", "result");
                body.add("    .sql($L)\n", sqlConstantName);
                ParamBindingCode.append(body, method);
                body.add("    .query(new $T())\n", rowMapperType);
                emitTerminal(body, method.resultKind(), ctx);
                body.add(";\n");
                emitHydrationCall(dialect, body, method.resultKind(), ctx);
                body.add("return $L;", "result");
            }
        } else {
            body.add("return $L\n", "jdbcClient");
            body.add("    .sql($L)\n", sqlConstantName);
            ParamBindingCode.append(body, method);
            body.add("    .query(new $T())\n", rowMapperType);
            emitTerminal(body, method.resultKind(), ctx);
            body.add(";");
        }
        builder.addCode(ExceptionTranslation.wrap(body.build()));
    }

    // ── Count ────────────────────────────────────────────────────────────

    private MethodSpec emitCount(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = overrideFor(ctx, method);

        String sql = selectSql.buildCountSql(ctx, method);
        String constBase = method.sqlConstantBase();
        String constName = ctx.registerConstant(constBase, sql);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("return jdbcClient\n");
        body.add("    .sql($L)\n", constName);
        ParamBindingCode.append(body, method);
        body.add("    .query($T.class).single();", Long.class);
        builder.addCode(ExceptionTranslation.wrap(body.build()));

        return builder.build();
    }

    // ── Exists ───────────────────────────────────────────────────────────

    private MethodSpec emitExists(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = overrideFor(ctx, method);

        String sql = selectSql.buildExistsSql(ctx, method);
        String constBase = method.sqlConstantBase();
        String constName = ctx.registerConstant(constBase, sql);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("return jdbcClient\n");
        body.add("    .sql($L)\n", constName);
        ParamBindingCode.append(body, method);
        body.add("    .query($T.class).single();", Boolean.class);
        builder.addCode(ExceptionTranslation.wrap(body.build()));

        return builder.build();
    }

    // ── Custom @Query methods ────────────────────────────────────────────

    /**
     * Generates a method override for a {@code @Query}-annotated method.
     * Uses the raw SQL from the annotation, binds parameters by name,
     * and handles the return type (SINGLE, LIST, OPTIONAL, etc.).
     */
    private MethodSpec emitCustomQuery(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = overrideFor(ctx, method);

        String sql = method.customSql();
        String constBase = method.sqlConstantBase();
        String constName = ctx.registerConstant(constBase, sql);

        ClassName rowMapperType = ctx.rowMapperType();
        Operation op = method.operation();

        CodeBlock.Builder body = CodeBlock.builder();

        if (op == Operation.DELETE) {
            // DELETE query: jdbcClient.sql(SQL).param(...).update()
            boolean isVoid = method.resultKind() == ResultKind.VOID;
            if (!isVoid) body.add("return ");
            body.add("jdbcClient\n");
            body.add("    .sql($L)\n", constName);
            ParamBindingCode.append(body, method);
            body.add("    .update();");
        } else {
            // SELECT query: jdbcClient.sql(SQL).param(...).query(new RowMapper()).terminal()
            body.add("return jdbcClient\n");
            body.add("    .sql($L)\n", constName);
            ParamBindingCode.append(body, method);
            body.add("    .query(new $T())\n", rowMapperType);
            emitTerminal(body, method.resultKind(), ctx);
            body.add(";");
        }

        builder.addCode(ExceptionTranslation.wrap(body.build()));
        return builder.build();
    }

    // ── Shared helpers ───────────────────────────────────────────────────

    private static String findPageRequestParam(MethodContext method) {
        for (VariableElement param : method.parameters()) {
            String fqn = param.asType().toString();
            if (fqn.contains("PageRequest")) {
                return param.getSimpleName().toString();
            }
        }
        return "pageRequest";
    }

    /**
     * Appends the terminal operation call to the code block.
     */
    private static void emitTerminal(CodeBlock.Builder body, ResultKind resultKind, RepositoryContext ctx) {
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

    // ── Hydration helpers ────────────────────────────────────────────────

    /**
     * Returns whether the entity has to-many collections that need hydration.
     */
    private static boolean hasToManyCollections(TypeDefinition entity) {
        for (TypeDefinition.Prop prop : entity.properties()) {
            if (prop instanceof TypeDefinition.Prop.EntityCollection
                    || prop instanceof TypeDefinition.Prop.ElementCollection) {
                return true;
            }
        }
        return false;
    }

    /**
     * Emits the hydration call appropriate for the result kind, but only if the entity has collections to hydrate.
     */
    private static void emitHydrationCall(SqlDialect dialect, CodeBlock.Builder body, ResultKind resultKind, RepositoryContext ctx) {
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

    // ── Select / count / exists SQL ──────────────────────────────────────

    /** Renders the SELECT (with composing / predicate-driven joins), COUNT and EXISTS statements. */
    private static final class SelectSql {

        private final SqlDialect dialect;

        SelectSql(SqlDialect dialect) {
            this.dialect = dialect;
        }

        String buildSelectSql(RepositoryContext ctx, MethodContext method) {
            SqlBuilder sqlBuilder = new SqlBuilder(ctx.entity());
            sqlBuilder.dialect(dialect);
        
            // Check if entity has composing associations that need JOIN projection
            List<TypeDefinition.Prop.Association> composingAssocs = getComposingAssociations(ctx.entity());
        
            // Also check if predicates traverse any associations (predicate-driven JOINs)
            List<TypeDefinition.Prop.Association> predicateDrivenAssocs = getPredicateDrivenAssociations(ctx, method);
        
            // Combine both sets of associations
            List<TypeDefinition.Prop.Association> allAssocs = new ArrayList<>();
            allAssocs.addAll(composingAssocs);
            for (TypeDefinition.Prop.Association assoc : predicateDrivenAssocs) {
                if (!allAssocs.contains(assoc)) {
                    allAssocs.add(assoc);
                }
            }
        
            if (!allAssocs.isEmpty()) {
                // Build SQL with LEFT JOINs for associations
                return buildSelectWithComposingJoins(ctx, allAssocs, method);
            }

            return sqlBuilder.sql(selectShell(method));
        }

        /**
         * Returns associations that are traversed in method predicates.
         * These need JOIN projection to fully materialize the target.
         */
        private List<TypeDefinition.Prop.Association> getPredicateDrivenAssociations(RepositoryContext ctx, MethodContext method) {
            List<TypeDefinition.Prop.Association> result = new ArrayList<>();
        
            for (Predicate pred : method.predicates()) {
                String propPath = pred.property();
                if (propPath.contains(".")) {
                    String firstSegment = propPath.substring(0, propPath.indexOf('.'));
                
                    // Check if this segment is an association
                    for (TypeDefinition.Prop prop : ctx.entity().properties()) {
                        if (prop instanceof TypeDefinition.Prop.Association assoc 
                            && assoc.name().equals(firstSegment)
                            && !result.contains(assoc)) {
                            result.add(assoc);
                        }
                    }
                }
            }
        
            return result;
        }

        /**
         * Returns all composing to-one associations for an entity.
         */
        private List<TypeDefinition.Prop.Association> getComposingAssociations(TypeDefinition entity) {
            List<TypeDefinition.Prop.Association> result = new ArrayList<>();
        
            // Handle both Entity and Aggregate types
            java.util.List<TypeDefinition.Prop.Association> associations;
            if (entity instanceof TypeDefinition.Entity e) {
                associations = e.associations();
            } else if (entity instanceof TypeDefinition.Aggregate a) {
                associations = a.associations();
            } else {
                return result; // ValueObject or other type - no associations
            }
        
            for (TypeDefinition.Prop.Association assoc : associations) {
                if (assoc.composition()) {
                    result.add(assoc);
                }
            }
            return result;
        }

        /**
         * Builds SELECT SQL with LEFT JOINs for composing associations.
         * Delegates to SqlBuilder with JoinEdge objects for proper SQL generation.
         */
        private String buildSelectWithComposingJoins(RepositoryContext ctx, 
                                                      List<TypeDefinition.Prop.Association> composingAssocs,
                                                      MethodContext method) {
            SqlBuilder sqlBuilder = new SqlBuilder(ctx.entity());
            sqlBuilder.dialect(dialect);
        
            // Build JoinEdge.ToOne objects for each composing association
            List<JoinEdge> joinEdges = new ArrayList<>();
            for (TypeDefinition.Prop.Association assoc : composingAssocs) {
                if (assoc.definition() instanceof TypeDefinition.Entity childEntity) {
                    // ToOne edge: owner.fkColumn = child.idColumn
                    joinEdges.add(new JoinEdge.ToOne(
                            new ColumnRef.ForeignKey(assoc.fkColumn(), ctx.entity().tableName()),
                            new ColumnRef.Simple(childEntity.idColumn(), childEntity.tableName())));
                }
            }
        
            // Build extra projection for composing association columns. The qualifier is the JOIN's
            // alias — the child table, by the one rule SqlFragment renders joins with — while the
            // column label keeps the property name the row mapper reads.
            StringBuilder extraProjection = new StringBuilder();
            for (TypeDefinition.Prop.Association assoc : composingAssocs) {
                if (assoc.definition() instanceof TypeDefinition.Entity childEntity) {
                    String joinAlias = dialect.quoteIdentifier(childEntity.tableName());
                    String columnPrefix = assoc.name() + "_join_";
                
                    for (TypeDefinition.Prop childProp : childEntity.properties()) {
                        if (childProp instanceof TypeDefinition.Prop.Scalar s) {
                            if (extraProjection.length() > 0) extraProjection.append(", ");
                            extraProjection.append(joinAlias).append(".").append(dialect.quoteIdentifier(s.columnName()))
                                .append(" AS ").append(dialect.quoteIdentifier(columnPrefix + s.columnName()));
                        } else if (childProp instanceof TypeDefinition.Prop.Id id) {
                            if (extraProjection.length() > 0) extraProjection.append(", ");
                            extraProjection.append(joinAlias).append(".").append(dialect.quoteIdentifier(id.columnName()))
                                .append(" AS ").append(dialect.quoteIdentifier(columnPrefix + id.columnName()));
                        } else if (childProp instanceof TypeDefinition.Prop.Embedded emb && emb.definition() != null) {
                            // Handle embedded VOs in the child entity
                            String embPrefix = columnPrefix + emb.name() + "_";
                            for (TypeDefinition.Prop embProp : emb.definition().properties()) {
                                if (embProp instanceof TypeDefinition.Prop.Scalar s) {
                                    if (extraProjection.length() > 0) extraProjection.append(", ");
                                    extraProjection.append(joinAlias).append(".").append(dialect.quoteIdentifier(emb.name() + "_" + s.columnName()))
                                        .append(" AS ").append(dialect.quoteIdentifier(embPrefix + s.columnName()));
                                }
                            }
                        }
                    }
                }
            }
        
            // Use SqlBuilder to render the query with JOINs
            return sqlBuilder.sql(selectShell(method), joinEdges, extraProjection.toString());
        }

        /**
         * Builds SELECT SQL without ORDER BY clause for dynamic Order handling.
         * This is used when the method has an Order<T> parameter that needs runtime resolution.
         */
        String buildSelectSqlWithoutOrderBy(RepositoryContext ctx, MethodContext method) {
            // Check if entity has composing associations that need JOIN projection
            List<TypeDefinition.Prop.Association> composingAssocs = getComposingAssociations(ctx.entity());
        
            if (!composingAssocs.isEmpty()) {
                // Build SQL with LEFT JOINs for composing associations (but no ORDER BY)
                return buildSelectWithComposingJoins(ctx, composingAssocs, method);
            }
        
            SqlBuilder sqlBuilder = new SqlBuilder(ctx.entity());
            sqlBuilder.dialect(dialect);

            // Empty sorts — ORDER BY is spliced at runtime from the Order parameter.
            return sqlBuilder.sql(selectShellUnordered(method));
        }

        String buildCountSql(RepositoryContext ctx, MethodContext method) {
            SqlBuilder sqlBuilder = new SqlBuilder(ctx.entity());
            sqlBuilder.dialect(dialect);
            return sqlBuilder.sql(countDefinition(method));
        }

        String buildExistsSql(RepositoryContext ctx, MethodContext method) {
            SqlBuilder sqlBuilder = new SqlBuilder(ctx.entity());
            sqlBuilder.dialect(dialect);
            return sqlBuilder.sql(existsDefinition(method));
        }
    }

    // ── Offset-paged finder ──────────────────────────────────────────────

    /** Offset-paged finder ({@link Window.Offset}), including the dynamic {@code Order} parameter support. */
    private static final class OffsetPageEmitter {

        private final SqlDialect dialect;
        private final SelectSql select;

        OffsetPageEmitter(SqlDialect dialect, SelectSql select) {
            this.dialect = dialect;
            this.select = select;
        }

        MethodSpec emitOffsetPagedFinder(MethodSpec.Builder builder, RepositoryContext ctx, MethodContext method) {
            String entityFqn = ctx.entity().typeName();
            ClassName entityType = ClassName.bestGuess(entityFqn);
            ClassName rowMapperType = ctx.rowMapperType();

            // Check if method has Order<T> parameter for dynamic sorting
            boolean hasOrderParam = hasOrderParameter(method);
            String orderParamName = hasOrderParam ? findOrderParamName(method) : null;

            // Build the base SELECT SQL (without ORDER BY if we have dynamic Order param)
            String selectSql;
            if (hasOrderParam) {
                // For dynamic Order, build SQL without ORDER BY - we'll add it at runtime
                selectSql = select.buildSelectSqlWithoutOrderBy(ctx, method);
            } else {
                selectSql = select.buildSelectSql(ctx, method);
            }
            String selectConstBase = method.sqlConstantBase();
            String selectConstName = ctx.registerConstant(selectConstBase, selectSql);

            // Build the COUNT SQL
            String countSql = select.buildCountSql(ctx, method);
            String countConstBase = method.sqlConstantBase("COUNT_");
            String countConstName = ctx.registerConstant(countConstBase, countSql);

            // Find the PageRequest parameter name
            String pageRequestParam = findPageRequestParam(method);

            CodeBlock.Builder body = CodeBlock.builder();

            // long offset = (pageRequest.page() - 1) * pageRequest.size();
            body.addStatement("long $L = ($L.page() - 1) * $L.size()", "offset", pageRequestParam, pageRequestParam);

            // If we have dynamic Order parameter, build SQL with ORDER BY at runtime
            if (hasOrderParam) {
                // String sql = SELECT_SQL;
                body.addStatement("$T $L = $L", String.class, "sql", selectConstName);
            
                // if (orderParam != null && !orderParam.isEmpty()) { ... }
                body.add("if ($L != null) {\n", orderParamName);
                body.addStatement("    java.util.List<String> $L = new java.util.ArrayList<>()", "orderClauses");
            
                // for (jakarta.data.Sort<?> sort : orderParam.sorts()) { ... }
                body.add("    for (jakarta.data.Sort<?> $L : $L.sorts()) {\n", "sort", orderParamName);
            
                // String prop = sort.property();
                body.addStatement("        String $L = $L.property()", "prop", "sort");
            
                // Compile-time switch over every resolvable sort path: local paths render their column
                // plus a runtime direction; relation paths render a pre-computed correlated aggregate
                // (MIN for ASC, MAX for DESC) whose direction is already embedded.
                Map<String, ColumnPlacement> orderPaths = collectDynamicOrderPaths(ctx.entity());
                SqlBuilder sortSql = new SqlBuilder(ctx.entity()).dialect(dialect);
                body.add("        String $L = switch ($L) {\n", "orderTerm", "prop");
                for (Map.Entry<String, ColumnPlacement> entry : orderPaths.entrySet()) {
                    String path = entry.getKey();
                    ColumnPlacement placement = entry.getValue();
                    if (placement.local()) {
                        String column = placement.leaf().column();
                        body.add("            case $S -> $S + ($L.isAscending() ? $S : $S);\n",
                                path, column, "sort", " ASC", " DESC");
                    } else {
                        String asc = sortSql.aggregateSortTerm(placement, true);
                        String desc = sortSql.aggregateSortTerm(placement, false);
                        body.add("            case $S -> $L.isAscending() ? $S : $S;\n",
                                path, "sort", asc, desc);
                    }
                }
                body.add("            default -> null;\n");
                body.add("        };\n");
                body.add("        if ($L != null) {\n", "orderTerm");
                body.addStatement("            $L.add($L)", "orderClauses", "orderTerm");
                body.add("        }\n");
                body.add("    }\n");
            
                // if (!orderClauses.isEmpty()) { sql += " ORDER BY " + String.join(", ", orderClauses); }
                body.add("    if (!$L.isEmpty()) {\n", "orderClauses");
                body.addStatement("        sql += $S + String.join($S, $L)", " ORDER BY ", ", ", "orderClauses");
                body.add("    }\n");
                body.add("}\n");
            
                // Now execute with the dynamically built SQL
                body.add("$T<$T> $L = jdbcClient\n", List.class, entityType, "content");
                body.add("    .sql($L + $S)\n", "sql", dialect.limitOffsetClause(":limit", ":offset"));
            } else {
                // Static SQL path (original behavior)
                body.add("$T<$T> $L = jdbcClient\n", List.class, entityType, "content");
                body.add("    .sql($L + $S)\n", selectConstName, dialect.limitOffsetClause(":limit", ":offset"));
            }
        
            body.add("    .param($S, $L.size())\n", "limit", pageRequestParam);
            body.add("    .param($S, $L)\n", "offset", "offset");
            ParamBindingCode.append(body, method);
            body.add("    .query(new $T())\n", rowMapperType);
            body.addStatement("    .list()");
        
            // Add hydration if entity has collections
            if (hasToManyCollections(ctx.entity())) {
                body.addStatement("$L($L)", "hydrateList", "content");
            }

            // long total = pageRequest.requestTotal() ? jdbcClient.sql(COUNT_SQL)...single() : 0;
            body.add("long $L = $L.requestTotal() ? jdbcClient\n", "total", pageRequestParam);
            body.add("    .sql($L)\n", countConstName);
            ParamBindingCode.append(body, method);
            body.addStatement("    .query($T.class).single() : 0", Long.class);

            // return new SimplePage<>(pageRequest, content, total);
            body.addStatement("return new $T<>($L, $L, $L)", SIMPLE_PAGE, pageRequestParam, "content", "total");

            builder.addCode(ExceptionTranslation.wrap(body.build()));
            return builder.build();
        }

        /**
         * The sortable paths for a dynamic {@code Order} parameter: the entity's local columns plus the
         * one-level reference traversals the offset finder can turn into a correlated aggregate sort term.
         */
        private Map<String, ColumnPlacement> collectDynamicOrderPaths(TypeDefinition entity) {
            Map<String, ColumnPlacement> paths = new LinkedHashMap<>();

            Map<String, ColumnRef> localRefs = entity.columnRefs();
            for (Map.Entry<String, ColumnRef> entry : localRefs.entrySet()) {
                paths.put(entry.getKey(), new ColumnPlacement(entry.getValue()));
            }

            for (TypeDefinition.Prop prop : entity.properties()) {
                if (prop instanceof TypeDefinition.Prop.Association assoc && assoc.definition() != null) {
                    JoinEdge.ToOne edge = buildAssociationEdge(entity, assoc);
                    if (edge == null) continue;
                    for (Map.Entry<String, ColumnRef> targetEntry
                            : assoc.definition().columnRefs().entrySet()) {
                        paths.put(assoc.name() + "." + targetEntry.getKey(),
                                new ColumnPlacement(targetEntry.getValue(), List.of(edge)));
                    }
                } else if (prop instanceof TypeDefinition.Prop.EntityCollection ec && ec.definition() != null) {
                    JoinEdge.ToMany edge = buildEntityCollectionEdge(entity, ec);
                    if (edge == null) continue;
                    for (Map.Entry<String, ColumnRef> targetEntry
                            : ec.definition().columnRefs().entrySet()) {
                        paths.put(ec.name() + "." + targetEntry.getKey(),
                                new ColumnPlacement(targetEntry.getValue(), List.of(edge)));
                    }
                }
            }
            return paths;
        }

        private JoinEdge.ToOne buildAssociationEdge(TypeDefinition owner, TypeDefinition.Prop.Association assoc) {
            if (assoc.definition() == null || assoc.definition().tableName() == null
                    || assoc.definition().idColumn() == null || assoc.fkColumn() == null) return null;
            return new JoinEdge.ToOne(
                    new ColumnRef.ForeignKey(assoc.fkColumn(), owner.tableName()),
                    new ColumnRef.Simple(assoc.definition().idColumn(), assoc.definition().tableName()));
        }

        private JoinEdge.ToMany buildEntityCollectionEdge(TypeDefinition owner, TypeDefinition.Prop.EntityCollection ec) {
            if (ec.definition() == null || ec.definition().tableName() == null
                    || ec.fkColumn() == null || owner.idColumn() == null) {
                return null;
            }
            return new JoinEdge.ToMany(
                    new ColumnRef.Simple(owner.idColumn(), owner.tableName()),
                    new ColumnRef.ForeignKey(ec.fkColumn(), ec.definition().tableName()));
        }

        /**
         * Checks if the method has an Order<T> parameter for dynamic runtime sorting.
         */
        private boolean hasOrderParameter(MethodContext method) {
            for (VariableElement param : method.parameters()) {
                String typeFqn = param.asType().toString();
                if (typeFqn.contains("jakarta.data.Order")) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Finds the parameter name of the Order<T> parameter.
         */
        private String findOrderParamName(MethodContext method) {
            for (VariableElement param : method.parameters()) {
                String simpleName = param.getSimpleName().toString();
                String typeFqn = param.asType().toString();
                if (typeFqn.contains("jakarta.data.Order")) {
                    return simpleName;
                }
            }
            return null;
        }
    }

    // ── Cursor-paged finder ──────────────────────────────────────────────

    /** Cursor-paged (keyset) finder ({@link Window.Cursor}). */
    private static final class CursorPageEmitter {

        private final SqlDialect dialect;
        private final SelectSql select;

        CursorPageEmitter(SqlDialect dialect, SelectSql select) {
            this.dialect = dialect;
            this.select = select;
        }

        /** The cursor key paths and directions: the sorts, then the optional id tie-breaker. */
        private record SortKeys(List<String> paths, List<Boolean> ascending) {
        }

        /** Per cursor key: the {@code ORDER BY} term (may be an alias) and the seek term (full expression). */
        private record SeekTerms(List<String> orderBy, List<String> seek) {
        }

        /** The child table, and its foreign key to the root, that a to-many sort path reads from. */
        private record ChildTable(String table, String fkColumn) {
        }

        MethodSpec emitCursorPagedFinder(MethodSpec.Builder builder, RepositoryContext ctx, MethodContext method) {
            TypeDefinition entity = ctx.entity();
            ClassName entityType = ClassName.bestGuess(entity.typeName());
            List<SortSpec> sorts = method.sorts();

            // A to-many sort is aggregated: through a LEFT JOIN of the first sorted entity collection (which is
            // hydrated after the query), otherwise through a correlated subquery.
            boolean hasToManySort = sorts.stream().anyMatch(sort -> startsWithToMany(entity, sort.property()));
            TypeDefinition.Prop.EntityCollection hydration = hydrationCollection(entity, sorts);

            SqlBuilder sqlBuilder = new SqlBuilder(entity);
            sqlBuilder.dialect(dialect);
            SqlBuilder.CursorProjection projection = cursorProjection(sqlBuilder, sorts, hydration);
            // No projection: unpaged, unordered shell; seek and order are spliced below
            String baseSql = projection != null ? projection.shell() : sqlBuilder.sql(selectShellUnordered(method));

            SortKeys keys = sortKeys(entity, sorts, hasToManySort);
            SeekTerms terms = seekTerms(entity, sorts, sqlBuilder, hydration, projection,
                    keys.paths().size() > sorts.size());
            // Shell + null-guarded keyset seek + ORDER BY, wrapped in a derived table when the seek compares
            // aggregates; core renders (and re-aliases) the structured ORDER BY terms.
            String seekStmt = sqlBuilder.pagedSeekStatement(baseSql, !method.predicates().isEmpty(), terms.seek(),
                    keys.ascending(), true, terms.orderBy(), seekAggregates(terms.seek(), projection),
                    dialect.quoteIdentifier(entity.tableName()));

            String seekConstName = ctx.registerConstant(method.sqlConstantBase("CURSOR_"), seekStmt);
            // Count SQL for the total (used when requestTotal)
            String countSql = select.buildCountSql(ctx, method);
            String countConstName = ctx.registerConstant(method.sqlConstantBase("COUNT_CURSOR_"), countSql);
            String pageRequestParam = findPageRequestParam(method);

            CodeBlock.Builder body = CodeBlock.builder();
            addContentQuery(body, ctx, method, seekConstName, pageRequestParam, terms.seek().size());
            if (hydration != null) {
                // Hydrate the to-many relation the cursor values are read from
                body.addStatement("$L($L)", "hydrateList", "content");
            }
            body.addStatement("$T<$T> $L = new $T<>()", List.class, CURSOR, "cursors", ArrayList.class);
            if (!keys.paths().isEmpty()) {
                addCursorExtraction(body, entity, entityType, sorts, keys);
            }
            addTotalAndReturn(body, method, pageRequestParam, countConstName);

            builder.addCode(ExceptionTranslation.wrap(body.build()));
            return builder.build();
        }

        // ── SQL ──────────────────────────────────────────────────────────

        /** Whether {@code path} is a dotted path whose head is an entity or element collection. */
        private static boolean startsWithToMany(TypeDefinition entity, String path) {
            return path.contains(".") && entity.isToManyPath(path);
        }

        /** The entity collection of the first dotted sort path that starts with one, or {@code null}. */
        private static TypeDefinition.Prop.EntityCollection hydrationCollection(TypeDefinition entity,
                                                                                List<SortSpec> sorts) {
            for (SortSpec sort : sorts) {
                String path = sort.property();
                if (path.contains(".")
                        && entity.findProperty(pathHead(path)) instanceof TypeDefinition.Prop.EntityCollection ec) {
                    return ec;
                }
            }
            return null;
        }

        /**
         * The LEFT JOIN / GROUP BY shell aggregating the sorts into the hydrated collection, or {@code null}
         * without a hydrated collection that has a resolved definition.
         */
        private static SqlBuilder.CursorProjection cursorProjection(SqlBuilder sqlBuilder, List<SortSpec> sorts,
                                                                    TypeDefinition.Prop.EntityCollection hydration) {
            if (hydration == null || hydration.definition() == null) {
                return null;
            }
            // child column -> ascending, in sort order
            Map<String, Boolean> sortColumns = new LinkedHashMap<>();
            for (SortSpec sort : sorts) {
                String path = sort.property();
                if (path.contains(".") && pathHead(path).equals(hydration.name())) {
                    sortColumns.put(childColumn(path), sort.ascending());
                }
            }
            return sqlBuilder.cursorSortProjection(hydration.definition().tableName(), hydration.fkColumn(),
                    hydration.name() + "_", sortColumns);
        }

        /** The sort paths and directions, plus the id (ascending) as a unique tail behind a to-many sort. */
        private static SortKeys sortKeys(TypeDefinition entity, List<SortSpec> sorts, boolean hasToManySort) {
            List<String> paths = new ArrayList<>();
            List<Boolean> ascending = new ArrayList<>();
            for (SortSpec sort : sorts) {
                paths.add(sort.property());
                ascending.add(sort.ascending());
            }
            if (hasToManySort) {
                SortSpec lastSort = sorts.getLast();
                TypeDefinition.Prop.Id id = entity.id();
                String idPropName = id != null ? id.name() : null;
                if (idPropName != null && !(lastSort.property().equals(idPropName) && lastSort.ascending())) {
                    paths.add(idPropName);
                    ascending.add(true);
                }
            }
            return new SortKeys(paths, ascending);
        }

        /**
         * One ORDER BY term and one seek term per sort: the projection's alias and aggregate for the hydrated
         * collection, a correlated subquery for other to-many paths, else the quoted (flattened) column. With
         * {@code idTieBreaker}, the root-qualified id column is appended.
         */
        private SeekTerms seekTerms(TypeDefinition entity, List<SortSpec> sorts, SqlBuilder sqlBuilder,
                                    TypeDefinition.Prop.EntityCollection hydration,
                                    SqlBuilder.CursorProjection projection, boolean idTieBreaker) {
            List<String> orderByTerms = new ArrayList<>();
            List<String> seekTerms = new ArrayList<>();
            int aggIndex = 0;
            for (SortSpec sort : sorts) {
                String path = sort.property();
                ChildTable child = path.contains(".") ? childTableOf(entity, path) : null;
                String orderByTerm;
                String seekTerm;
                if (child == null) {
                    orderByTerm = dialect.quoteIdentifier(path.replace('.', '_'));
                    seekTerm = orderByTerm;
                } else if (hydration != null && pathHead(path).equals(hydration.name())) {
                    orderByTerm = projection.orderByTerms().get(aggIndex);
                    seekTerm = projection.seekTerms().get(aggIndex);
                    aggIndex++;
                } else {
                    orderByTerm = sqlBuilder.correlatedSort(child.table(), childColumn(path), child.fkColumn(),
                            entity.tableName(), entity.idColumn(), sort.ascending());
                    seekTerm = orderByTerm;
                }
                orderByTerms.add(orderByTerm);
                seekTerms.add(seekTerm);
            }
            if (idTieBreaker) {
                // Qualified with the root table to avoid ambiguity with joined tables
                String qualifiedId = dialect.quoteIdentifier(entity.tableName()) + "."
                        + dialect.quoteIdentifier(entity.idColumn());
                orderByTerms.add(qualifiedId);
                seekTerms.add(qualifiedId);
            }
            return new SeekTerms(orderByTerms, seekTerms);
        }

        /**
         * The child table of the dotted {@code path}'s head: a resolved entity collection or an element
         * collection; {@code null} for any other head.
         */
        private static ChildTable childTableOf(TypeDefinition entity, String path) {
            TypeDefinition.Prop head = entity.findProperty(pathHead(path));
            if (head instanceof TypeDefinition.Prop.EntityCollection ec && ec.definition() != null) {
                return new ChildTable(ec.definition().tableName(), ec.fkColumn());
            } else if (head instanceof TypeDefinition.Prop.ElementCollection el) {
                return new ChildTable(el.childTable(), el.fkColumn());
            }
            return null;
        }

        /** The projection's aggregate seek terms when the seek compares MIN/MAX aggregates, else {@code null}. */
        private static List<String> seekAggregates(List<String> seekTerms, SqlBuilder.CursorProjection projection) {
            boolean hasAggregates = seekTerms.stream().anyMatch(term -> term.contains("MIN(") || term.contains("MAX("));
            return hasAggregates && projection != null ? projection.seekTerms() : null;
        }

        /** The segment before the first dot of a dotted path. */
        private static String pathHead(String path) {
            return path.substring(0, path.indexOf('.'));
        }

        /** The child column of a dotted path: everything after the head, with dots flattened to underscores. */
        private static String childColumn(String path) {
            return path.substring(path.indexOf('.') + 1).replace('.', '_');
        }

        // ── Method body ──────────────────────────────────────────────────

        /** {@code var content = jdbcClient.sql(seek + LIMIT)} with the limit, cursor and predicate bindings. */
        private void addContentQuery(CodeBlock.Builder body, RepositoryContext ctx, MethodContext method,
                                     String seekConstName, String pageRequestParam, int cursorKeyCount) {
            body.addStatement("$T sql = $L + $S", String.class, seekConstName, dialect.limitClause(":limit"));
            body.add("var $L = jdbcClient\n", "content");
            body.add("    .sql(sql)\n");
            body.add("    .param($S, $L.size())\n", "limit", pageRequestParam);
            for (int i = 0; i < cursorKeyCount; i++) {
                body.add("    .param($S, $L.cursor().map(c -> c.get($L)).orElse(null))\n",
                        "cursor" + i, pageRequestParam, i);
            }
            ParamBindingCode.append(body, method);
            body.add("    .query(new $T())\n", ctx.rowMapperType());
            body.addStatement("    .list()");
        }

        /** {@code sortPaths}, {@code sortDirs}, a {@code ValueReader} over the key paths, and one cursor per row. */
        private static void addCursorExtraction(CodeBlock.Builder body, TypeDefinition entity, ClassName entityType,
                                                List<SortSpec> sorts, SortKeys keys) {
            addAsList(body, String.class, "sortPaths", "$S", keys.paths());
            addAsList(body, Boolean.class, "sortDirs", "$L", keys.ascending());

            body.add("$T<$T> $L = (path, entity) -> {\n", VALUE_ACCESS, entityType, "access");
            body.add("    return switch (path) {\n");
            for (String path : keys.paths()) {
                addValueReaderCase(body, entity, entityType, sorts, path);
            }
            body.add("        default -> throw new $T($S + path);\n",
                    IllegalArgumentException.class, "Unknown path: ");
            body.add("    };\n");
            body.addStatement("};");

            body.addStatement("for ($T row : content) {\n" +
                    "    cursors.add($T.forEntity(sortPaths, sortDirs, access, row));\n" +
                    "}", entityType, CURSOR_KEYS);
        }

        /** {@code List<type> name = Arrays.asList(...);} with each value rendered by {@code format}. */
        private static void addAsList(CodeBlock.Builder body, Class<?> type, String name, String format,
                                      List<?> values) {
            body.add("$T<$T> $L = $T.asList(", List.class, type, name, Arrays.class);
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) body.add(", ");
                body.add(format, values.get(i));
            }
            body.add(");\n");
        }

        /** One {@code case} of the {@code ValueReader} switch: a getter, or the values along a collection path. */
        private static void addValueReaderCase(CodeBlock.Builder body, TypeDefinition entity, ClassName entityType,
                                               List<SortSpec> sorts, String path) {
            if (!path.contains(".")) {
                body.add("        case $S -> entity.$L();\n", path, getterName(path));
                return;
            }
            String[] parts = path.split("\\.");
            String elementTypeName = resolveCollectionElementType(entity, parts[0]);
            SortSpec matchingSort = sorts.stream().filter(s -> s.property().equals(path)).findFirst().orElse(null);
            String itemVar = parts[0] + "i";

            body.add("        case $S -> {\n", path);
            body.add("            var $L = entity.$L();\n", "v0", getterName(parts[0]));
            body.add("            if ($L instanceof $T) {\n", "v0", List.class);
            if (entity.isToManyPath(path) && matchingSort != null) {
                // A to-many sort reads the MIN/MAX element value, matching the SQL aggregate
                if (elementTypeName != null) {
                    body.add("                var $L = (($T<?>) $L).stream().map($L -> $L).toList();\n",
                            "values", List.class, "v0", itemVar, elementChain(elementTypeName, itemVar, parts));
                    String aggCall = matchingSort.ascending()
                            ? "$L.stream().min(java.util.Comparator.naturalOrder()).orElse(null)"
                            : "$L.stream().max(java.util.Comparator.naturalOrder()).orElse(null)";
                    body.add("                yield $L.isEmpty() ? null : " + aggCall + ";\n", "values", "values");
                } else {
                    body.add("                yield null;\n");
                }
            } else if (elementTypeName != null) {
                body.add("                yield (($T<?>) $L).stream().map($L -> $L).toList();\n",
                        List.class, "v0", itemVar, elementChain(elementTypeName, itemVar, parts));
            } else {
                // Fallback: cast the items to the entity type
                body.add("                yield (($T<?>) $L).stream().map($L -> (($T) $L).$L()).toList();\n",
                        List.class, "v0", itemVar, entityType, itemVar, getterName(parts[parts.length - 1]));
            }
            // A non-collection intermediate value; unreachable for paths that start at a collection
            body.add("            } else {\n");
            body.add("                yield null;\n");
            body.add("            }\n");
            body.add("        }\n");
        }

        /** {@code ((Element) item).getB().getC()} for the segments after the collection head. */
        private static String elementChain(String elementTypeName, String itemVar, String[] parts) {
            StringBuilder chain = new StringBuilder("((" + ClassName.bestGuess(elementTypeName) + ") " + itemVar + ")");
            for (int i = 1; i < parts.length; i++) {
                chain.append(".").append(getterName(parts[i])).append("()");
            }
            return chain.toString();
        }

        /** The element type of the collection named {@code head}, or {@code null} if it is not a collection. */
        private static String resolveCollectionElementType(TypeDefinition entity, String head) {
            TypeDefinition.Prop prop = entity.findProperty(head);
            if (prop instanceof TypeDefinition.Prop.EntityCollection ec) {
                return ec.definition() != null ? ec.definition().typeName() : ec.javaType();
            } else if (prop instanceof TypeDefinition.Prop.ElementCollection el) {
                return el.javaType();
            }
            return null;
        }

        /** The getter name for {@code name}: {@code getName}. */
        private static String getterName(String name) {
            return "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }

        /** {@code long total = ...} (counted only when requested) and the returned {@code SimpleCursoredPage}. */
        private static void addTotalAndReturn(CodeBlock.Builder body, MethodContext method, String pageRequestParam,
                                              String countConstName) {
            body.add("long $L = $L.requestTotal() ? jdbcClient.sql($L)", "total", pageRequestParam, countConstName);
            ParamBindingCode.append(body, method);
            body.add(".query($T.class).single() : 0", Long.class);
            body.addStatement("");
            body.addStatement("return new $T<>($L, $L, $L, $L)",
                    SIMPLE_CURSORED_PAGE, pageRequestParam, "content", "cursors", "total");
        }
    }
}
