package io.github.fludakit.data.processor.generator;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.NonLocalAttributePathException;
import io.github.fludakit.data.metadata.UnknownAttributePathException;
import io.github.fludakit.data.page.SimpleCursoredPage;
import io.github.fludakit.data.page.SimplePage;
import io.github.fludakit.data.processor.analysis.MappedTypeBuilder;
import io.github.fludakit.data.processor.analysis.PathResolver;
import io.github.fludakit.data.processor.generator.metadata.MetadataGenerator;
import io.github.fludakit.data.processor.generator.EmissionContext;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.analysis.PagedRead;
import io.github.fludakit.data.processor.analysis.PathResolution;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.query.SortSpec;
import io.github.fludakit.data.sql.CursorKeys;
import io.github.fludakit.data.sql.SqlBuilder;
import io.github.fludakit.data.sql.SqlDialect;
import jakarta.data.page.PageRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * Emits the body of a read that declares {@code Page<E>} or {@code CursoredPage<E>}.
 *
 * <p>Three things make this its own emitter rather than another branch of the read shapes:</p>
 *
 * <ol>
 *   <li>A paged read has TWO statements, not one — the page and its total — and the cursored shape
 *       has a third: the fetch-one-extra-row probe that answers {@code hasNext}.</li>
 *   <li>The seek predicate is a COMPILE-TIME constant per direction: only the cursor values and the
 *       page direction are runtime inputs, so both renderings ({@code _SEEK_NEXT} / {@code _SEEK_PREV})
 *       are baked here instead of being assembled per query.</li>
 *   <li>The cursor keys are built from the SAME property/direction lists that produced the ORDER BY,
 *       so those two cannot drift — which is why the sort loop lives here and not with the caller.</li>
 * </ol>
 *
 * <p>A sort that traverses a to-many is the one case that cannot resolve to a flat column; it falls
 * back to the compile-time path resolution and renders the aggregate subquery term, then appends the
 * root id so the keyset stays unique. Any OTHER sort the flat column view cannot answer is rejected
 * here, as an ERROR naming the property against the method that declared it — a declared sort is a
 * user error of exactly the kind an unresolvable predicate is, and gets the same channel.</p>
 */
public final class PagingEmitter {

    private final SqlDialect sqlDialect;
    private final MappedTypeBuilder typeMappingBuilder;
    private final PathResolver pathResolver;
    private final MetadataGenerator metadataGenerator;
    private final Diagnostics diagnostics;

    public PagingEmitter(SqlDialect sqlDialect, MappedTypeBuilder typeMappingBuilder,
                         PathResolver pathResolver, MetadataGenerator metadataGenerator,
                         Diagnostics diagnostics) {
        this.sqlDialect = sqlDialect;
        this.typeMappingBuilder = typeMappingBuilder;
        this.pathResolver = pathResolver;
        this.metadataGenerator = metadataGenerator;
        this.diagnostics = diagnostics;
    }

    /**
     * @param ctx  the repository-fixed read inputs. Taking them WHOLE removes the {@code boolean hydrate}
     *             that used to sit as the fifth positional argument beside the entity, the constants and
     *             the mapper — a bare {@code true}/{@code false} among four values that are always passed
     *             together, where the call site read {@code emitPaged(builder, ctx.entity(), ctx.constants(),
     *             ctx.mapperType(), ctx.hydrate(), read)} and meant nothing on its own.
     * @param read the per-method inputs, including the method a rejected sort is reported against
     *
     * <p>Leaves {@code builder} EMPTY and returns without emitting when a declared sort property does
     * not resolve; the ERROR naming the property is reported against that method, and no statements
     * have been added at that point because the whole body is appended after the sort loop.</p>
     */
    public void emitPaged(MethodSpec.Builder builder, EmissionContext ctx, PagedRead read) {
        List<SortSpec> effective = read.sorts().isEmpty()
                ? List.of(SortSpec.of(ctx.entity().id().name(), true, false))
                : read.sorts();
        String entityFqn = ctx.entity().typeName().toString();
        ClassName metadataClass = metadataGenerator.metadataNameFor(entityFqn);
        List<String> properties = new ArrayList<>();
        List<String> directions = new ArrayList<>();
        List<String> terms = new ArrayList<>();
        boolean toManySort = false;
        for (SortSpec s : effective) {
            String column;
            ColumnPlacement aggregatePlacement = null;
            try {
                column = new SqlBuilder(ctx.entity(), ctx.entity()).dialect(sqlDialect).resolveColumn(s.property());
            } catch (NonLocalAttributePathException crossing) {
                // The path EXISTS but lands in another table, so the flat column view cannot express
                // it. The compile model may still answer it as a TO-MANY head — that renders the
                // aggregate subquery term (the same string the runtime isToManyPath/aggregateSortTerm
                // pair produces). This is the recovery branch, not an error: a to-many sort is legal.
                PathResolution resolution = pathResolver.resolveCompilePath(ctx.entity(), s.property());
                if (resolution.ref() != null && resolution.existential()) {
                    aggregatePlacement = resolution.placement();
                    column = null;
                    toManySort = true;
                } else {
                    diagnostics.error("'" + crossing.path() + "' traverses a relationship and is not a"
                            + " sortable property of " + ctx.entity().typeName(), read.method());
                    return;
                }
            } catch (UnknownAttributePathException unknown) {
                // Rejected WHERE IT IS RECOGNISED, naming the property: a sort is DECLARED — it came
                // from the method name or an @OrderBy — so an unresolvable one is the same user error
                // an unresolvable predicate is, and it gets the same ERROR on the same declaration.
                // Rethrowing instead reached the per-repository net, which reported the whole
                // repository as "not supported" without ever naming the property.
                diagnostics.error("'" + unknown.path() + "' is not a sortable property of "
                        + ctx.entity().typeName(), read.method());
                return;
            }
            properties.add(s.property());
            directions.add(Boolean.toString(s.ascending()));
            terms.add(column != null ? sqlDialect.quoteIdentifier(column)
                    : new SqlBuilder(ctx.entity()).dialect(sqlDialect)
                            .aggregateSortTerm(aggregatePlacement, s.ascending()));
        }
        if (toManySort && !("true".equals(directions.get(directions.size() - 1))
                && ctx.entity().id().name().equals(properties.get(properties.size() - 1)))) {
            // Keyset uniqueness: the aggregate edge is not unique per root row — append the
            // root-qualified id ASC term and grow props/dirs (index-aligned) with "id",
            // skipped only when the last sort already IS the ascending id.
            properties.add(ctx.entity().id().name());
            directions.add("true");
            terms.add(sqlDialect.quoteIdentifier(ctx.entity().tableName()) + "."
                    + sqlDialect.quoteIdentifier(ctx.entity().idColumn()));
        }
        String propsExpr = "java.util.List.of(" + quoted(properties) + ")";
        String dirsExpr = "java.util.List.of(" + String.join(", ", directions) + ")";
        String orderBy;
        if (toManySort) {
            StringBuilder ob = new StringBuilder(" ORDER BY ");
            for (int i = 0; i < terms.size(); i++) {
                if (i > 0) {
                    ob.append(", ");
                }
                ob.append(terms.get(i)).append("true".equals(directions.get(i)) ? " ASC" : " DESC");
            }
            orderBy = ob.toString();
        } else {
            orderBy = new SqlBuilder(ctx.entity(), ctx.entity()).dialect(sqlDialect).orderBy(effective);
        }
        String wherePrefix = read.paramBindings().isEmpty() ? " WHERE " : " AND ";

        if (!read.cursored()) {
            String countConstant = SqlConstants.register(ctx.constants(), "SQL_COUNT_ALL",
                    new SqlBuilder(ctx.entity()).dialect(sqlDialect).countBy(List.of()));
            builder.addStatement("long offset = ($L.page() - 1) * $L.size()", read.pageRequest(), read.pageRequest());
            CodeBlock.Builder call = CodeBlock.builder()
                    .add("jdbcClient.sql($L + $S + $S + $L.size() + $S + offset)", read.findConstant(), orderBy,
                            sqlDialect.limitClause(""), read.pageRequest(), sqlDialect.offsetClause(""));
            QueryInvocations.appendBoundParameters(call, read.paramBindings(), read.parameters());
            call.add(".query($L).list()", QueryInvocations.queryArg(typeMappingBuilder.classNameFor(ctx.entity().typeName()), ctx.mapperType()));
            builder.addStatement("$T<$T> content = $L", List.class, typeMappingBuilder.classNameFor(ctx.entity().typeName()), call.build());
            builder.addStatement("long total = $L.requestTotal() ? jdbcClient.sql($L).singleValue($T.class) : -1L",
                    read.pageRequest(), countConstant, Long.class);
            if (ctx.hydrate()) {
                builder.addStatement("$L", QueryInvocations.hydrationCall("content"));
            }
            builder.addStatement("return new $T<>($L, content, total)", SimplePage.class, read.pageRequest());
            return;
        }

        builder.addStatement("$T.Cursor cursor = $L.cursor().orElse(null)", PageRequest.class, read.pageRequest());
        builder.beginControlFlow("if (cursor == null)");
        CodeBlock.Builder offsetCall = CodeBlock.builder()
                .add("jdbcClient.sql($L + $S + $S + $L.size())", read.findConstant(), orderBy,
                        sqlDialect.limitClause(""), read.pageRequest());
        QueryInvocations.appendBoundParameters(offsetCall, read.paramBindings(), read.parameters());
        offsetCall.add(".query($L).list()", QueryInvocations.queryArg(typeMappingBuilder.classNameFor(ctx.entity().typeName()), ctx.mapperType()));
        builder.addStatement("$T<$T> content = $L", List.class, typeMappingBuilder.classNameFor(ctx.entity().typeName()), offsetCall.build());
        if (ctx.hydrate()) {
            builder.addStatement("$L", QueryInvocations.hydrationCall("content"));
        }
        builder.addStatement("$T<$T.Cursor> cursors = new $T<>()", List.class, PageRequest.class, ArrayList.class);
        builder.beginControlFlow("for ($T e : content)", typeMappingBuilder.classNameFor(ctx.entity().typeName()));
        builder.addStatement("cursors.add($T.forEntity($L, $L, $T.INSTANCE, e))",
                CursorKeys.class, propsExpr, dirsExpr, metadataClass);
        builder.endControlFlow();
        builder.addStatement("return new $T<>($L, content, cursors, -1L)", SimpleCursoredPage.class, read.pageRequest());
        builder.endControlFlow();
        // The seek predicate is a compile-time constant per direction: only the cursor VALUES and the
        // page direction are runtime inputs, so both renderings are baked here rather than assembled
        // per query. Each carries the base predicates, its own seek clause and the shared ORDER BY;
        // the fetch-one-extra-row limit stays a runtime splice.
        List<Boolean> ascending = directions.stream().map(Boolean::parseBoolean).toList();
        String findConstantSql = ctx.constants().get(read.findConstant());
        String suffix = sqlDialect.limitClause("");
        String nextConstant = SqlConstants.register(ctx.constants(), read.findConstant() + "_SEEK_NEXT", findConstantSql
                + wherePrefix + seek(ctx.entity(), terms, ascending, true) + orderBy + suffix);
        String prevConstant = SqlConstants.register(ctx.constants(), read.findConstant() + "_SEEK_PREV", findConstantSql
                + wherePrefix + seek(ctx.entity(), terms, ascending, false) + orderBy + suffix);
        CodeBlock.Builder seekCall = CodeBlock.builder()
                .add("jdbcClient.sql(($L.mode() == $T.Mode.CURSOR_NEXT ? $L : $L) + $L.size() + $S)",
                        read.pageRequest(), PageRequest.class, nextConstant, prevConstant, read.pageRequest(), " + 1");
        QueryInvocations.appendBoundParameters(seekCall, read.paramBindings(), read.parameters());
        for (int i = 0; i < terms.size(); i++) {
            seekCall.add(".param($S, cursor.get($L))", "cursor" + i, i);
        }
        seekCall.add(".query($L).list()", QueryInvocations.queryArg(typeMappingBuilder.classNameFor(ctx.entity().typeName()), ctx.mapperType()));
        builder.addStatement("$T<$T> content = $L", List.class, typeMappingBuilder.classNameFor(ctx.entity().typeName()), seekCall.build());
        builder.addStatement("boolean hasMore = content.size() > $L.size()", read.pageRequest());
        builder.beginControlFlow("if (hasMore)");
        builder.addStatement("content = content.subList(0, $L.size())", read.pageRequest());
        builder.endControlFlow();
        if (ctx.hydrate()) {
            builder.addStatement("$L", QueryInvocations.hydrationCall("content"));
        }
        builder.addStatement("$T<$T.Cursor> cursors = new $T<>()", List.class, PageRequest.class, ArrayList.class);
        builder.beginControlFlow("for ($T e : content)", typeMappingBuilder.classNameFor(ctx.entity().typeName()));
        builder.addStatement("cursors.add($T.forEntity($L, $L, $T.INSTANCE, e))",
                CursorKeys.class, propsExpr, dirsExpr, metadataClass);
        builder.endControlFlow();
        builder.addStatement("return new $T<>($L, content, cursors, -1L)", SimpleCursoredPage.class, read.pageRequest());
    }

    /** The keyset seek predicate for {@code terms} in the given direction, rendered by {@link SqlBuilder}. */
    private String seek(TypeMapping<?> entity, List<String> terms, List<Boolean> ascending, boolean forward) {
        return new SqlBuilder(entity).dialect(sqlDialect).seekClause(terms, ascending, forward);
    }

    private String quoted(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(", ");
            sb.append('"').append(v).append('"');
        }
        return sb.toString();
    }
}
