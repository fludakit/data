package io.github.fludakit.data.processor.generator;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.JoinEdge;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.page.SimplePage;
import io.github.fludakit.data.processor.analysis.TypeModelBuilder;
import io.github.fludakit.data.processor.analysis.PathResolver;
import io.github.fludakit.data.processor.generator.metadata.Literals;
import io.github.fludakit.data.processor.generator.metadata.TypeMappingGenerator;
import io.github.fludakit.data.processor.generator.RepositoryContext;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.query.LimitSpec;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;
import io.github.fludakit.data.sql.QueryCompiler;
import io.github.fludakit.data.sql.SqlBuilder;
import io.github.fludakit.data.sql.SqlDialect;
import jakarta.data.Sort;

import javax.lang.model.element.VariableElement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The reads a repository interface INHERITS rather than declares: {@code findById}, {@code findAll}
 * and the paged {@code findAll(PageRequest, Sort)}.
 *
 * <p>All three answer from one {@link RepositoryContext} and they differ only in their predicate and in
 * their terminal, which is what puts them in one class: the terminal is chosen from the declared
 * return shape, and a mistake there compiles into the consumer's application.</p>
 *
 * <p>Two things are special to this family and are the reason it is not just another branch of the
 * method compiler:</p>
 *
 * <ul>
 *   <li>A read whose composing to-ones are JOIN-projected must use the projected constant as its base
 *       — the qualified variant would drop the {@code <field>_join_<column>} labels the row mapper
 *       reads ({@link #joinedReadSql}).</li>
 *   <li>The paged {@code findAll} defers its ORDER BY to RUNTIME, because the caller passes the sort:
 *       it resolves each {@code Sort} through the descriptor at runtime, so it needs a second,
 *       ROOT-QUALIFIED constant for the case where the sort traverses a relation.</li>
 * </ul>
 */
public final class CrudReadEmitter {

    private static final ClassName MD_JOIN_EDGE =
            ClassName.get("io.github.fludakit.data.metadata", "JoinEdge");
    private static final ClassName MD_COLUMN_PLACEMENT =
            ClassName.get("io.github.fludakit.data.metadata", "ColumnPlacement");

    private final SqlDialect sqlDialect;
    private final TypeModelBuilder typeMappingBuilder;
    private final TypeMappingGenerator metadataGenerator;
    private final JoinedProjectionEmitter joinedProjections;

    public CrudReadEmitter(SqlDialect sqlDialect, TypeModelBuilder typeMappingBuilder,
                           TypeMappingGenerator metadataGenerator,
                           JoinedProjectionEmitter joinedProjections) {
        this.sqlDialect = sqlDialect;
        this.typeMappingBuilder = typeMappingBuilder;
        this.metadataGenerator = metadataGenerator;
        this.joinedProjections = joinedProjections;
    }

    /**
     * Renders a CRUD read that JOIN-projects the composing to-one references instead of re-reading
     * them: the root columns stay in schema order, each target's flattened leaves ride the same row
     * under {@code <field>_join_<column>} labels, and the join chain is a plain {@code LEFT JOIN} per
     * target so a parent with no target still comes back.
     *
     * <p>A to-one join contributes at most one row per parent, so it cannot fan out and cannot move
     * LIMIT or COUNT onto join rows — which is precisely why to-one is safe to project and to-many is
     * not.</p>
     */
    private String joinedReadSql(RepositoryContext ctx, List<Predicate> predicates, List<SortSpec> sorts,
                                 int limit) {
        TypeMapping<?> mapping = ctx.entity();
        List<JoinEdge> edges = new ArrayList<>();
        for (Prop.Reference ref : ctx.joinedReferences()) {
            if (ref.targetTable() == null || ref.targetIdColumn() == null) {
                continue;
            }
            JoinEdge edge = PathResolver.toOneOrNull(mapping.tableName(), ref.fkColumn(),
                    ref.targetTable(), ref.targetIdColumn());
            if (edge != null) {
                edges.add(edge);
            }
        }
        String extraProjection = joinedProjections.joinedProjection(mapping, ctx.joinedReferences());
        QueryDefinition.Read model = new QueryDefinition.Read(ResultKind.LIST, predicates, sorts,
                PagingMode.NONE, new LimitSpec(limit, 0), List.of(), List.of(), false);
        return QueryCompiler.render(mapping, mapping, sqlDialect, model, edges,
                extraProjection.isEmpty() ? null : extraProjection);
    }

    /** The row mapper a read uses: the JOIN-projection mapper when one was generated, else the base one. */
    private CodeBlock readMapper(RepositoryContext ctx) {
        return ctx.crudFullMapper() != null
                ? CodeBlock.of("(rs, rowNum) -> $L(rs, rowNum)", ctx.crudFullMapper())
                : QueryInvocations.queryArg(typeMappingBuilder.classNameFor(ctx.entity().typeName()), ctx.mapperType());
    }

    public void emitFindById(MethodSpec.Builder builder, String value, RepositoryContext ctx, ResultKind kind) {
        List<Predicate> predicates = List.of(Predicate.of(ctx.entity().id().name(), Operator.EQ, MemberNames.ID_PARAM));
        boolean joined = ctx.crudFullMapper() != null;
        String constant = SqlConstants.register(ctx.constants(), joined ? "SQL_FIND_BY_ID_JOINED" : "SQL_FIND_BY_ID",
                joined ? joinedReadSql(ctx, predicates, List.of(), 0)
                        : new SqlBuilder(ctx.entity()).dialect(sqlDialect)
                                .selectBy(predicates, List.of(), 0, null));
        // The declared return type decides the terminal: Optional<E> keeps the empty case, a bare E
        // must not — .optional() there does not compile against the declaration.
        String terminal = kind == ResultKind.OPTIONAL ? "optional()" : "single()";
        if (!ctx.hydrate()) {
            builder.addStatement("return jdbcClient.sql($L).param($S, $L).query($L).$L",
                    constant, MemberNames.ID_PARAM, value, readMapper(ctx), terminal);
            return;
        }
        builder.addStatement("$T<$T> found = jdbcClient.sql($L).param($S, $L).query($L).optional()",
                java.util.Optional.class, typeMappingBuilder.classNameFor(ctx.entity().typeName()), constant, MemberNames.ID_PARAM, value, readMapper(ctx));
        builder.addStatement("found.ifPresent(e -> $L)", QueryInvocations.hydrationCall("java.util.List.of(e)"));
        builder.addStatement("return $L", kind == ResultKind.OPTIONAL ? "found" : "found.get()");
    }

    public void emitFindAll(MethodSpec.Builder builder, RepositoryContext ctx, ResultKind kind) {
        boolean joined = ctx.crudFullMapper() != null;
        String constant = SqlConstants.register(ctx.constants(), joined ? "SQL_FIND_ALL_JOINED" : "SQL_FIND_ALL",
                joined ? joinedReadSql(ctx, List.of(), List.of(), 0)
                        : new SqlBuilder(ctx.entity()).dialect(sqlDialect)
                                .selectBy(List.of(), List.of(), 0, null));
        // The declared shape decides the terminal: CrudRepository declares Stream<E>, but an
        // application may declare List<E>/Set<E>/E[], which .stream() does not satisfy.
        if (!ctx.hydrate()) {
            if (kind == ResultKind.SET) {
                builder.addStatement("return new $T<>(jdbcClient.sql($L).query($L).list())",
                        LinkedHashSet.class, constant, readMapper(ctx));
                return;
            }
            if (kind == ResultKind.ARRAY) {
                builder.addStatement("return jdbcClient.sql($L).query($L).list().toArray($T[]::new)",
                        constant, readMapper(ctx), typeMappingBuilder.classNameFor(ctx.entity().typeName()));
                return;
            }
            builder.addStatement("return jdbcClient.sql($L).query($L).$L", constant,
                    readMapper(ctx), kind == ResultKind.LIST ? "list()" : "stream()");
            return;
        }
        // Materialize before streaming so the (lazy) stream's elements are all hydrated up front.
        builder.addStatement("$T<$T> results = jdbcClient.sql($L).query($L).list()",
                List.class, typeMappingBuilder.classNameFor(ctx.entity().typeName()), constant, readMapper(ctx));
        builder.addStatement("$L", QueryInvocations.hydrationCall("results"));
        if (kind == ResultKind.SET) {
            builder.addStatement("return new $T<>(results)", LinkedHashSet.class);
            return;
        }
        if (kind == ResultKind.ARRAY) {
            builder.addStatement("return results.toArray($T[]::new)", typeMappingBuilder.classNameFor(ctx.entity().typeName()));
            return;
        }
        builder.addStatement("return $L", kind == ResultKind.LIST ? "results" : "results.stream()");
    }

    public void emitFindAllPage(MethodSpec.Builder builder, List<? extends VariableElement> parameters,
                                 RepositoryContext ctx) {
        String pageRequest = MemberNames.parameterName(parameters.get(0), "pageRequest");
        String sortBy = MemberNames.parameterName(parameters.get(1), "sortBy");
        // When composing to-one targets are JOIN-projected, the base constant already carries their
        // joins and their labelled columns, so it is the ONLY valid base — the qualified variant would
        // drop the labels the row mapper reads. A to-one join adds at most one row per parent, so the
        // runtime LIMIT/COUNT still count roots.
        boolean joined = ctx.crudFullMapper() != null;
        String findConstant = SqlConstants.register(ctx.constants(), joined ? "SQL_FIND_ALL_JOINED" : "SQL_FIND_ALL",
                joined ? joinedReadSql(ctx, List.of(), List.of(), 0)
                        : new SqlBuilder(ctx.entity()).dialect(sqlDialect)
                                .selectBy(List.of(), List.of(), 0, null));
        String countConstant = SqlConstants.register(ctx.constants(), "SQL_COUNT_ALL",
                new SqlBuilder(ctx.entity()).dialect(sqlDialect).countBy(List.of()));
        // a runtime sort can traverse a relation and splice a LEFT JOIN onto this constant —
        // if the joined table shares a column name (entity targets always share "id") the bare select
        // list becomes ambiguous. When the descriptor has ANY traversable prop, register a second,
        // ROOT-TABLE-QUALIFIED find constant (the joined method-find shape) and pick it at
        // runtime when joins are non-empty; the labels are unchanged by qualification, so the same
        // row mapper reads both. Relation-free descriptors keep the single-constant emission
        // byte-identical (legacy parity).
        boolean joinCapable = joined;
        for (Prop p : ctx.entity().props()) {
            if (PathResolver.isTraversable(p)) {
                joinCapable = true;
                break;
            }
        }
        // A JOIN-projected base already IS the qualified shape, so it doubles as the joined variant —
        // emitting SQL_FIND_ALL_QUALIFIED here would give the runtime a base without the labels.
        String joinedFindConstant = joinCapable
                ? (joined ? findConstant
                        : SqlConstants.register(ctx.constants(), "SQL_FIND_ALL_QUALIFIED",
                                new SqlBuilder(ctx.entity()).dialect(sqlDialect)
                                        .qualifiedRootSelect(List.of(), true, "")))
                : null;
        // The base constant of a JOIN-projected read already carries a LEFT JOIN per composing to-one
        // target. A runtime sort that traverses the same target resolves to an edge onto that SAME
        // table, and splicing it again repeats the alias, making every reference to it ambiguous — so
        // the emission hands the runtime the tables the base already joined and lets it drop those
        // edges. With no projection there is nothing to suppress and the legacy call is kept verbatim.
        String alreadyJoined = joined ? projectedJoinTables(ctx.joinedReferences()) : null;

        String entityFqn = typeMappingBuilder.classNameFor(ctx.entity().typeName()).toString();
        ClassName metadataClass = metadataGenerator.metadataNameFor(entityFqn);
        builder.addStatement("$T sql = new $T($T.INSTANCE).dialect(SQL_DIALECT)",
                SqlBuilder.class, SqlBuilder.class, metadataClass);

        builder.addStatement("$T<$T> joinRefs = new $T<>()", List.class, MD_JOIN_EDGE, ArrayList.class);
        builder.addStatement("$T orderBy = new $T()", StringBuilder.class, StringBuilder.class);
        if (joinCapable) {
            builder.addStatement("boolean keyed = false");
        }
        builder.beginControlFlow("for ($T<?> s : $L)", Sort.class, sortBy);
        // The descriptor resolves the sort property directly: its navigation list is fixed, so
        // resolve() is one switch dispatch. The placement carries both halves the runtime needs —
        // the edges to splice and the column to qualify — so they cannot be handed over mismatched.
        builder.addStatement("$T r = $T.INSTANCE.resolve(s.property())", MD_COLUMN_PLACEMENT, metadataClass);
        builder.beginControlFlow("if (orderBy.length() > 0)");
        builder.addStatement("orderBy.append($S)", ", ");
        builder.endControlFlow();
        if (joinCapable) {
            builder.beginControlFlow("if (sql.isToManyPath(s.property()))");
            builder.addStatement("keyed = true");
            builder.nextControlFlow("else");
            builder.addStatement("joinRefs.addAll(r.edges())");
            builder.endControlFlow();
            builder.addStatement("orderBy.append(sql.isToManyPath(s.property())"
                            + " ? sql.aggregateSortTerm(r, s.isAscending())"
                            + " : sql.qualify(r.leaf())).append(s.isAscending() ? $S : $S)",
                    " ASC", " DESC");
            builder.endControlFlow();
        } else {
            builder.addStatement("orderBy.append(sql.qualify(r.leaf()))"
                            + ".append(s.isAscending() ? $S : $S)",
                    " ASC", " DESC");
            builder.endControlFlow();
        }
        if (alreadyJoined == null) {
            builder.addStatement("$T joins = sql.joinClause(joinRefs)", String.class);
        } else {
            builder.addStatement("$T joins = sql.joinClause(joinRefs, $T.of($L))",
                    String.class, List.class, alreadyJoined);
        }

        builder.addStatement("long offset = ($L.page() - 1) * $L.size()", pageRequest, pageRequest);
        if (joinCapable) {
            builder.addStatement("$T<$T> content = jdbcClient.sql(((joins.isEmpty() && !keyed ? $L : $L) + joins"
                            + " + (orderBy.length() > 0 ? $S + orderBy : $S)"
                            + " + $S + $L.size() + $S + offset)).query($L).list()",
                    List.class, typeMappingBuilder.classNameFor(ctx.entity().typeName()), findConstant, joinedFindConstant, " ORDER BY ", "",
                    sqlDialect.limitClause(""), pageRequest, sqlDialect.offsetClause(""),
                    readMapper(ctx));
        } else {
            builder.addStatement("$T<$T> content = jdbcClient.sql($L + joins + (orderBy.length() > 0 ? $S + orderBy : $S)"
                            + " + $S + $L.size() + $S + offset).query($L).list()",
                    List.class, typeMappingBuilder.classNameFor(ctx.entity().typeName()), findConstant, " ORDER BY ", "",
                    sqlDialect.limitClause(""), pageRequest, sqlDialect.offsetClause(""),
                    readMapper(ctx));
        }
        builder.addStatement("long total = $L.requestTotal() ? jdbcClient.sql($L).singleValue($T.class) : -1L",
                pageRequest, countConstant, Long.class);
        if (ctx.hydrate()) {
            builder.addStatement("$L", QueryInvocations.hydrationCall("content"));
        }
        builder.addStatement("return new $T<>($L, content, total)", SimplePage.class, pageRequest);
    }

    /**
     * The {@code List.of("t1", "t2")} argument listing the tables a JOIN-projected base constant
     * already joins, or {@code ""} when no reference contributes a table. Empty means the projection
     * emitted no join (a target whose table is unresolved), and the runtime then splices every edge
     * exactly as before.
     */
    private String projectedJoinTables(List<Prop.Reference> joinedReferences) {
        StringBuilder sb = new StringBuilder();
        for (Prop.Reference ref : joinedReferences) {
            if (ref.targetTable() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append('"').append(ref.targetTable()).append('"');
        }
        return sb.toString();
    }
}
