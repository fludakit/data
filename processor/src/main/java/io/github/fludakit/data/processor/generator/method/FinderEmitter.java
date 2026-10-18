package io.github.fludakit.data.processor.generator.method;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.analysis.MethodResults;
import io.github.fludakit.data.processor.analysis.TypeModelResolver;
import io.github.fludakit.data.processor.analysis.PathResolver;
import io.github.fludakit.data.processor.model.PagedRead;
import io.github.fludakit.data.processor.model.QueryPlan;
import io.github.fludakit.data.processor.model.ResolvedMethod;
import io.github.fludakit.data.processor.model.ResolvedPredicate;
import io.github.fludakit.data.processor.model.ResolvedSort;
import io.github.fludakit.data.processor.analysis.QueryPlanBuilder;
import io.github.fludakit.data.processor.analysis.RepositoryMethodResolver;
import io.github.fludakit.data.processor.generator.RepositoryContext;
import io.github.fludakit.data.processor.generator.JoinedProjectionEmitter;
import io.github.fludakit.data.processor.generator.PagingEmitter;
import io.github.fludakit.data.processor.generator.QueryInvocations;
import io.github.fludakit.data.processor.generator.SqlConstants;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.GeneratedArtifacts;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.LimitSpec;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SeekTerm;
import io.github.fludakit.data.query.SortSpec;
import io.github.fludakit.data.sql.SqlBuilder;
import io.github.fludakit.data.sql.SqlDialect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.ExecutableType;

/**
 * The shared emitter behind the two finder shapes — {@code @Find} and query-by-method-name.
 *
 * <p>Both resolve the same things (predicates, sorts, parameter bindings, result kind) and render the
 * same {@code SqlBuilder} statements; they differ only in where the criteria come from (the
 * annotation's {@code @By}/{@code @OrderBy} or the parsed method name) and in which operation the name
 * states. The two {@link MethodGenerator}s that own those shapes delegate to this one emitter, so the
 * find/count/exists/delete-by-criteria path is written once.
 */
public final class FinderEmitter {

    private final TypeModelResolver typeMappingBuilder;
    private final Diagnostics diagnostics;
    private final SqlDialect sqlDialect;
    private final QueryPlanBuilder queryPlanCompiler;
    private final JoinedProjectionEmitter joinedProjections;
    private final PagingEmitter pagingEmitter;
    private final GeneratedArtifacts registry;
    private final MethodResults methodResults;
    private final RepositoryMethodResolver resolver;

    public FinderEmitter(TypeModelResolver typeMappingBuilder, Diagnostics diagnostics, SqlDialect sqlDialect,
                         QueryPlanBuilder queryPlanCompiler,
                         JoinedProjectionEmitter joinedProjections, PagingEmitter pagingEmitter,
                         GeneratedArtifacts registry, MethodResults methodResults, RepositoryMethodResolver resolver) {
        this.typeMappingBuilder = typeMappingBuilder;
        this.diagnostics = diagnostics;
        this.sqlDialect = sqlDialect;
        this.queryPlanCompiler = queryPlanCompiler;
        this.joinedProjections = joinedProjections;
        this.pagingEmitter = pagingEmitter;
        this.registry = registry;
        this.methodResults = methodResults;
        this.resolver = resolver;
    }

    /**
     * Folds a resolved {@link QueryPlan} into the published {@link QueryDefinition.Criteria}. The
     * finder emitter is its only caller, so it lives here rather than as a top-level helper.
     */
    private static final class PlanPublisher {

        private PlanPublisher() {
        }

        static QueryDefinition.Criteria queryDefinitionOf(Operation operation,
                                                          ResultKind kind, ResultKind resultKind,
                                                          QueryPlan plan, boolean distinct) {
            List<Predicate> logicalPredicates = new ArrayList<>();
            for (ResolvedPredicate rp : plan.predicates()) {
                Predicate predicate = rp.predicate();
                String authoritative = rp.attributePath();
                logicalPredicates.add(Predicate.of(
                        authoritative != null ? authoritative
                                : resolvedAttributePath(plan.mapping(), rp.placement(), predicate.property()),
                        predicate.operator(), predicate.param(), predicate.connector(), predicate.ignoreCase()));
            }
            List<SortSpec> logicalSorts = new ArrayList<>();
            for (ResolvedSort rs : plan.sorts()) {
                SortSpec sort = rs.sort();
                String sortPath = rs.attributePath();
                logicalSorts.add(SortSpec.of(
                        sortPath != null ? sortPath
                                : resolvedAttributePath(plan.mapping(), rs.placement(), sort.property()),
                        sort.ascending(), sort.ignoreCase()));
            }
            PagingMode paging = kind == ResultKind.PAGE ? PagingMode.OFFSET
                    : kind == ResultKind.CURSORED_PAGE ? PagingMode.CURSOR : PagingMode.NONE;
            List<SeekTerm> seekTerms = paging == PagingMode.NONE ? List.of()
                    : seekTerms(plan.mapping(), plan.sorts(), logicalSorts);
            QueryDefinition.Criteria definition = switch (operation) {
                case COUNT -> new QueryDefinition.Count(logicalPredicates, plan.paramBindings(), distinct);
                case EXISTS -> new QueryDefinition.Exists(logicalPredicates, plan.paramBindings());
                case DELETE -> new QueryDefinition.DeleteWhere(logicalPredicates, plan.paramBindings(), resultKind);
                default -> new QueryDefinition.Read(resultKind, logicalPredicates, logicalSorts,
                        paging, new LimitSpec(plan.limit(), 0), seekTerms, plan.paramBindings(), distinct);
            };
            return definition;
        }

        private static String resolvedAttributePath(TypeMapping<?> mapping, ColumnPlacement placement, String fallback) {
            if (placement.local()) {
                for (Map.Entry<String, ColumnRef> entry : mapping.columnRefs().entrySet()) {
                    if (entry.getValue().equals(placement.leaf())) return entry.getKey();
                }
            }
            return fallback;
        }

        private static List<SeekTerm> seekTerms(TypeMapping<?> mapping, List<ResolvedSort> sorts, List<SortSpec> logicalSorts) {
            ColumnRef idRef = new ColumnRef.Simple(mapping.idColumn(), mapping.tableName());
            if (sorts.isEmpty()) {
                return List.of(new SeekTerm(idRef, true));
            }
            List<SeekTerm> out = new ArrayList<>();
            for (int i = 0; i < sorts.size(); i++) {
                out.add(new SeekTerm(sorts.get(i).placement().leaf(), logicalSorts.get(i).ascending()));
            }
            boolean toManySort = sorts.stream().anyMatch(ResolvedSort::existential);
            SortSpec last = logicalSorts.getLast();
            boolean lastIsIdAsc = mapping.id().name().equals(last.property()) && last.ascending();
            if (toManySort && !lastIsIdAsc) {
                out.add(new SeekTerm(idRef, true));
            }
            return out;
        }
    }

    /**
     * Emits the implementation of one finder method. {@code explicitFind} is {@code true} for an
     * {@code @Find} method (criteria from {@code @By} / parameter names), {@code explicitDelete} is
     * {@code true} for an {@code @Delete}-annotated parameter-derived delete; otherwise the method
     * name is parsed as a query-by-method-name.
     */
    public void emit(MethodSpec.Builder builder, ExecutableElement method, ExecutableType resolved,
                     RepositoryContext emission, boolean explicitFind, boolean explicitDelete) {
        new Emission(builder, method, resolved, emission, emission.auxMethods(),
                emission.repositoryFqn(), emission.queryDefinitionNames(), explicitFind, explicitDelete).run();
    }

    /** One method's emission: the per-method state the private helpers share. */
    private final class Emission {
        private final MethodSpec.Builder builder;
        private final ExecutableElement method;
        private final ExecutableType resolved;
        private final RepositoryContext ctx;
        private final List<MethodSpec> auxMethods;
        private final String repositoryFqn;
        private final Map<String, Integer> queryDefinitionNames;
        private final boolean explicitFind;
        private final boolean explicitDelete;

        Emission(MethodSpec.Builder builder, ExecutableElement method, ExecutableType resolved,
                 RepositoryContext ctx, List<MethodSpec> auxMethods, String repositoryFqn,
                 Map<String, Integer> queryDefinitionNames, boolean explicitFind, boolean explicitDelete) {
            this.builder = builder;
            this.method = method;
            this.resolved = resolved;
            this.ctx = ctx;
            this.auxMethods = auxMethods;
            this.repositoryFqn = repositoryFqn;
            this.queryDefinitionNames = queryDefinitionNames;
            this.explicitFind = explicitFind;
            this.explicitDelete = explicitDelete;
        }

        void run() {
            ResolvedMethod input = resolver.resolveFindMethod(method, resolved, ctx.entity(),
                    explicitFind, explicitDelete, repositoryFqn);
            if (input == null) {
                return;
            }
            if (input.operation() == Operation.COUNT || input.operation() == Operation.EXISTS) {
                emitCountOrExists(input);
            } else if (input.operation() == Operation.DELETE) {
                emitDelete(input);
            } else {
                emitFindRead(input);
            }
        }

        /**
         * Builds this method's {@link QueryDefinition.Criteria}, publishes it under the generated field
         * name in the session registry (the source the {@code <Repository>Queries} holder is written
         * from), and returns it so the caller renders the SQL constant from the very same object.
         */
        private QueryDefinition.Criteria publish(ResolvedMethod input, QueryPlan plan, boolean distinct,
                Operation operation, ResultKind kind, ResultKind resultKind) {
            String queryDefinitionName = MemberNames.capitalize(input.name()) + "Query";
            int nameClash = queryDefinitionNames.merge(queryDefinitionName, 1, Integer::sum);
            if (nameClash > 1) {
                queryDefinitionName = queryDefinitionName + nameClash;
            }
            QueryDefinition.Criteria definition =
                    PlanPublisher.queryDefinitionOf(operation, kind, resultKind, plan, distinct);
            registry.queryDefinitions(repositoryFqn).put(queryDefinitionName, definition);
            return definition;
        }

        private void emitCountOrExists(ResolvedMethod input) {
            QueryDefinition.Criteria definition = publish(input,
                    new QueryPlan(ctx.entity(), input.predicates(), List.of(), 0, input.paramBindings(), input.parameters()),
                    input.distinct(), input.operation(), input.kind(), input.kind());
            String constant = SqlConstants.register(ctx.constants(), "SQL_" + MemberNames.upperSnake(input.name()),
                    new SqlBuilder(ctx.entity(), ctx.entity(), definition).dialect(sqlDialect).count());
            CodeBlock.Builder call = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
            QueryInvocations.appendBoundParameters(call, input.paramBindings(), input.parameters());
            call.add(".singleValue($T.class)", Long.class);
            if (input.operation() == Operation.EXISTS) {
                builder.addStatement("return $L > 0", call.build());
            } else if (input.kind() == ResultKind.VOID) {
                builder.addStatement("$L", call.build());
            } else {
                builder.addStatement("return $L", call.build());
            }
        }

        private void emitDelete(ResolvedMethod input) {
            // A delete whose predicate traverses more than one relation would need a nested EXISTS,
            // which is not rendered yet: degrade each such predicate to a bare column with a null path.
            List<ResolvedPredicate> deletePredicates = new ArrayList<>();
            for (ResolvedPredicate rp : input.predicates()) {
                if (rp.placement() != null && rp.placement().edges().size() > 1) {
                    diagnostics.note("path '" + rp.predicate().property()
                            + "' traverses more than one relation; not supported", method);
                    deletePredicates.add(new ResolvedPredicate(rp.predicate(),
                            PathResolver.placementOf(PathResolver.snakeCasedLocalColumnRef(rp.predicate().property())), null));
                } else {
                    deletePredicates.add(rp);
                }
            }
            QueryDefinition.Criteria definition = publish(input,
                    new QueryPlan(ctx.entity(), deletePredicates, List.of(), 0, input.paramBindings(), input.parameters()),
                    false, Operation.DELETE, input.kind(),
                    methodResults.writeResultKindOf(input.returnMirror(), input.kind(), ctx.entity()));
            String constant = SqlConstants.register(ctx.constants(), "SQL_" + MemberNames.upperSnake(input.name()),
                    new SqlBuilder(ctx.entity(), ctx.entity(), definition).dialect(sqlDialect).delete());
            CodeBlock.Builder call = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
            QueryInvocations.appendBoundParameters(call, input.paramBindings(), input.parameters());
            call.add(".update()");
            if (input.kind() == ResultKind.VOID) {
                builder.addStatement("$L", call.build());
            } else {
                builder.addStatement("return $L", call.build());
            }
        }

        private void emitFindRead(ResolvedMethod input) {
            if (input.kind() == ResultKind.CURSORED_PAGE || input.kind() == ResultKind.PAGE) {
                emitPagedFind(input);
            } else {
                emitPlainFind(input);
            }
        }

        private void emitPagedFind(ResolvedMethod input) {
            String pageRequest = queryPlanCompiler.specialParameter(input.parameters(), input.parameterTypes(), QueryPlanBuilder.PAGE_REQUEST);
            if (pageRequest == null) {
                diagnostics.error(
                        input.name() + " returning " + (input.kind() == ResultKind.PAGE ? "Page" : "CursoredPage")
                                + " requires a PageRequest parameter", method);
                return;
            }
            if (!joinedProjections.collectTraversedReferences(ctx.entity(),
                    input.predicates().stream().map(ResolvedPredicate::placement).toList(), List.of()).isEmpty()) {
                diagnostics.note(
                        input.name() + ": full materialisation through joins is not supported for paged finders;"
                                + " association fields materialize as id-only partials", method);
            }
            QueryDefinition.Criteria definition = publish(input,
                    new QueryPlan(ctx.entity(), input.predicates(), input.resolvedSorts(), 0, input.paramBindings(), input.parameters()),
                    input.distinct(), Operation.FIND, input.kind(), input.kind());
            String constant = SqlConstants.register(ctx.constants(), "SQL_" + MemberNames.upperSnake(input.name()),
                    new SqlBuilder(ctx.entity(), ctx.entity(), definition).dialect(sqlDialect).select());
            pagingEmitter.emitPaged(builder, ctx, new PagedRead(method, pageRequest, input.sorts(), constant,
                    input.paramBindings(), input.parameters(), input.kind() == ResultKind.CURSORED_PAGE));
        }

        private void emitPlainFind(ResolvedMethod input) {
            List<ColumnPlacement> sortRefs = input.resolvedSorts().stream().map(ResolvedSort::placement).toList();

            List<Prop.Reference> traversed = joinedProjections.collectTraversedReferences(ctx.entity(),
                    input.predicates().stream().map(ResolvedPredicate::placement).toList(), sortRefs);
            boolean fullMaterialisation = !traversed.isEmpty() && ctx.mapperType() != null
                    && input.operation() == Operation.FIND && input.kind() != ResultKind.VOID;
            String extraProjection = fullMaterialisation ? joinedProjections.joinedProjection(ctx.entity(), traversed) : "";
            CodeBlock queryArgForMethod = QueryInvocations.queryArg(
                    typeMappingBuilder.classNameFor(ctx.entity().typeName()), ctx.mapperType());
            if (fullMaterialisation) {
                String fullMapper = "mapRow_full_" + Names.toSnakeCase(input.name());
                auxMethods.add(joinedProjections.buildFullMapper(fullMapper, ctx.entity(), ctx.mapperType(), traversed));
                queryArgForMethod = CodeBlock.of("(rs, rowNum) -> $L(rs, rowNum)", fullMapper);
            }
            QueryDefinition.Criteria queryDefinition = publish(input,
                    new QueryPlan(ctx.entity(), input.predicates(), input.resolvedSorts(),
                            input.parsed() != null ? ((io.github.fludakit.data.qbn.ParsedMethodName) input.parsed()).limit() : 0, input.paramBindings(), input.parameters()),
                    input.distinct(), Operation.FIND, input.kind(), input.kind());
            String sql = new SqlBuilder(ctx.entity(), ctx.entity(), queryDefinition)
                    .dialect(sqlDialect).select(extraProjection);
            String constant = SqlConstants.register(ctx.constants(), "SQL_" + MemberNames.upperSnake(input.name()), sql);
            CodeBlock.Builder call = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
            QueryInvocations.appendBoundParameters(call, input.paramBindings(), input.parameters());
            emitFindTerminal(input, call, queryArgForMethod);
        }

        private void emitFindTerminal(ResolvedMethod input, CodeBlock.Builder call, CodeBlock queryArgForMethod) {
            if (input.kind() == ResultKind.ARRAY) {
                call.add(".query($L).list()", queryArgForMethod);
                if (!ctx.hydrate()) {
                    builder.addStatement("return $L.toArray($T[]::new)", call.build(), methodResults.queryResultType(input.returnMirror(),
                            typeMappingBuilder.classNameFor(ctx.entity().typeName())));
                } else {
                    builder.addStatement("$T<$T> results = $L",
                            List.class, typeMappingBuilder.classNameFor(ctx.entity().typeName()), call.build());
                    builder.addStatement("$L", QueryInvocations.hydrationCall("results"));
                    builder.addStatement("return results.toArray($T[]::new)",
                            methodResults.queryResultType(input.returnMirror(), typeMappingBuilder.classNameFor(ctx.entity().typeName())));
                }
                return;
            }
            if (!ctx.hydrate() || input.kind() == ResultKind.VOID) {
                QueryInvocations.terminal(call, input.kind(), queryArgForMethod);
                if (input.kind() == ResultKind.VOID) {
                    builder.addStatement("$L", call.build());
                } else {
                    builder.addStatement("return $L", call.build());
                }
                return;
            }
            switch (input.kind()) {
                case LIST, STREAM -> {
                    call.add(".query($L).list()", queryArgForMethod);
                    builder.addStatement("$T<$T> results = $L", List.class, typeMappingBuilder.classNameFor(ctx.entity().typeName()), call.build());
                    builder.addStatement("$L", QueryInvocations.hydrationCall("results"));
                    builder.addStatement(input.kind() == ResultKind.STREAM ? "return results.stream()" : "return results");
                }
                case OPTIONAL -> {
                    call.add(".query($L).optional()", queryArgForMethod);
                    builder.addStatement("$T<$T> found = $L", java.util.Optional.class, typeMappingBuilder.classNameFor(ctx.entity().typeName()), call.build());
                    builder.addStatement("found.ifPresent(e -> $L)", QueryInvocations.hydrationCall("java.util.List.of(e)"));
                    builder.addStatement("return found");
                }
                case SINGLE -> {
                    call.add(".query($L).single()", queryArgForMethod);
                    builder.addStatement("$T found = $L", typeMappingBuilder.classNameFor(ctx.entity().typeName()), call.build());
                    builder.addStatement("$L", QueryInvocations.hydrationCall("java.util.List.of(found)"));
                    builder.addStatement("return found");
                }
                default -> {
                    QueryInvocations.terminal(call, input.kind(), queryArgForMethod);
                    builder.addStatement("$L", call.build());
                }
            }
        }
    }
}
