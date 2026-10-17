package io.github.fludakit.data.processor.generator.method;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.analysis.TypeModelBuilder;
import io.github.fludakit.data.processor.model.PathOrigin;
import io.github.fludakit.data.processor.model.PathResolution;
import io.github.fludakit.data.processor.analysis.PathResolver;
import io.github.fludakit.data.processor.model.PagedRead;
import io.github.fludakit.data.processor.model.QueryPlan;
import io.github.fludakit.data.processor.analysis.QueryPlanBuilder;
import io.github.fludakit.data.processor.generator.EmissionContext;
import io.github.fludakit.data.processor.generator.JoinedProjectionEmitter;
import io.github.fludakit.data.processor.generator.PagingEmitter;
import io.github.fludakit.data.processor.generator.QueryInvocations;
import io.github.fludakit.data.processor.generator.RepositoryEmission;
import io.github.fludakit.data.processor.generator.SqlConstants;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.support.GeneratedArtifactRegistry;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;
import io.github.fludakit.data.sql.SqlBuilder;
import io.github.fludakit.data.sql.SqlDialect;
import jakarta.data.repository.By;
import jakarta.data.repository.OrderBy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;

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

    private final TypeModelBuilder typeMappingBuilder;
    private final Diagnostics diagnostics;
    private final SqlDialect sqlDialect;
    private final PathResolver pathResolver;
    private final QueryPlanBuilder queryPlanCompiler;
    private final JoinedProjectionEmitter joinedProjections;
    private final PagingEmitter pagingEmitter;
    private final GeneratedArtifactRegistry registry;
    private final MethodResults methodResults;

    public FinderEmitter(TypeModelBuilder typeMappingBuilder, Diagnostics diagnostics, SqlDialect sqlDialect,
                  PathResolver pathResolver, QueryPlanBuilder queryPlanCompiler,
                  JoinedProjectionEmitter joinedProjections, PagingEmitter pagingEmitter,
                  GeneratedArtifactRegistry registry, MethodResults methodResults) {
        this.typeMappingBuilder = typeMappingBuilder;
        this.diagnostics = diagnostics;
        this.sqlDialect = sqlDialect;
        this.pathResolver = pathResolver;
        this.queryPlanCompiler = queryPlanCompiler;
        this.joinedProjections = joinedProjections;
        this.pagingEmitter = pagingEmitter;
        this.registry = registry;
        this.methodResults = methodResults;
    }

    /**
     * Emits the implementation of one finder method. {@code explicitFind} is {@code true} for an
     * {@code @Find} method (criteria from {@code @By} / parameter names), {@code explicitDelete} is
     * {@code true} for an {@code @Delete}-annotated parameter-derived delete; otherwise the method
     * name is parsed as a query-by-method-name.
     */
    public void emit(MethodSpec.Builder builder, ExecutableElement method, ExecutableType resolved,
                     RepositoryEmission emission, boolean explicitFind, boolean explicitDelete) {
        new Emission(builder, method, resolved, emission.readContext(), emission.auxMethods(),
                emission.repositoryFqn(), emission.queryDefinitionNames(), explicitFind, explicitDelete).run();
    }

    /**
     * Everything the find/count/delete emitters need from one repository method: the parsed action, the
     * argument bindings, the predicates and their compile-time column refs, and the collision-suffixed
     * query-definition name.
     */
    private record FindInput(String name, List<? extends VariableElement> parameters,
                             Object parsed, Operation operation, boolean distinct,
                             List<? extends TypeMirror> parameterTypes, List<ParamBinding> paramBindings,
                             TypeMirror returnMirror, ResultKind kind, String queryDefinitionName,
                             List<Predicate> predicates, List<ColumnPlacement> predicateRefs,
                             List<String> predicateAttributePaths) {
    }

    /**
     * Resolves everything a find/count/delete emitter needs from one method.
     *
     * @return the resolved input, or {@code null} when a declared predicate property does not resolve —
     *         the ERROR is already reported, so the method must be skipped
     */
    private FindInput resolveFindInput(ExecutableElement method, ExecutableType resolved,
                                       TypeMapping<?> entity, Map<String, Integer> queryDefinitionNames,
                                       boolean explicitFind, boolean explicitDelete) {
        String name = method.getSimpleName().toString();
        List<? extends VariableElement> parameters = method.getParameters();
        Object parsed = (explicitFind || explicitDelete) ? null : queryPlanCompiler.tryParse(name);
        Operation operation = explicitDelete ? Operation.DELETE
                : explicitFind ? Operation.FIND
                : parsed != null ? ((io.github.fludakit.data.qbn.ParsedMethodName) parsed).operation() : Operation.FIND;
        boolean distinct = parsed != null && ((io.github.fludakit.data.qbn.ParsedMethodName) parsed).distinct()
                && (operation == Operation.FIND || operation == Operation.COUNT);
        List<? extends TypeMirror> parameterTypes = resolved.getParameterTypes();
        QueryPlanBuilder.ResolvedBindings resolvedBindings = queryPlanCompiler.resolvedBindings(parameters, parameterTypes, explicitFind || explicitDelete, parsed);
        List<Predicate> predicates = resolvedBindings.predicates();
        List<ParamBinding> paramBindings = resolvedBindings.paramBindings();
        TypeMirror returnMirror = resolved.getReturnType();
        ResultKind kind = methodResults.resultKindOf(returnMirror);
        String queryDefinitionName = MemberNames.capitalize(name) + "Query";
        int nameClash = queryDefinitionNames.merge(queryDefinitionName, 1, Integer::sum);
        if (nameClash > 1) {
            queryDefinitionName = queryDefinitionName + nameClash; // overload collision: declaration-order suffix
        }
        boolean annotatedParams = parameters.stream().anyMatch(p -> p.getAnnotation(By.class) != null);
        PathOrigin origin = (parsed != null || annotatedParams) ? PathOrigin.DECLARED : PathOrigin.INFERRED;
        List<PathResolution> predicatePaths = pathResolver.resolveCompilePaths(entity,
                predicates.stream().map(Predicate::property).toList(), method, origin);
        if (origin.failsOnUnresolved() && predicatePaths.stream().anyMatch(p -> p.placement() == null)) {
            return null;
        }
        return new FindInput(name, parameters, parsed, operation, distinct, parameterTypes, paramBindings,
                returnMirror, kind, queryDefinitionName, predicates,
                predicatePaths.stream().map(PathResolution::placement).toList(),
                predicatePaths.stream().map(PathResolution::attributePath).toList());
    }

    /** One method's emission: the per-method state the private helpers share. */
    private final class Emission {
        private final MethodSpec.Builder builder;
        private final ExecutableElement method;
        private final ExecutableType resolved;
        private final EmissionContext ctx;
        private final List<MethodSpec> auxMethods;
        private final String repositoryFqn;
        private final Map<String, Integer> queryDefinitionNames;
        private final boolean explicitFind;
        private final boolean explicitDelete;

        Emission(MethodSpec.Builder builder, ExecutableElement method, ExecutableType resolved,
                 EmissionContext ctx, List<MethodSpec> auxMethods, String repositoryFqn,
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
            FindInput input = resolveFindInput(method, resolved, ctx.entity(), queryDefinitionNames,
                    explicitFind, explicitDelete);
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
        private QueryDefinition.Criteria publish(FindInput input, QueryPlan plan, boolean distinct,
                Operation operation, ResultKind kind, ResultKind resultKind) {
            QueryDefinition.Criteria definition =
                    QueryPlanBuilder.queryDefinitionOf(operation, kind, resultKind, plan, distinct);
            registry.queryDefinitions(repositoryFqn).put(input.queryDefinitionName(), definition);
            return definition;
        }

        private void emitCountOrExists(FindInput input) {
            QueryDefinition.Criteria definition = publish(input,
                    new QueryPlan(ctx.entity(), input.predicates(), input.predicateRefs(),
                            input.predicateAttributePaths(), List.of(), List.of(), List.of(), List.of(), 0,
                            input.paramBindings(), input.parameters()),
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

        private void emitDelete(FindInput input) {
            List<ColumnPlacement> predicateRefs = input.predicateRefs();
            List<String> predicatePaths = input.predicateAttributePaths();
            List<ColumnPlacement> deleteRefs = predicateRefs;
            List<String> deletePaths = predicatePaths;
            for (int i = 0; i < predicateRefs.size(); i++) {
                ColumnPlacement ref = predicateRefs.get(i);
                if (ref != null && ref.edges().size() > 1) {
                    diagnostics.note(
                            "path '" + input.predicates().get(i).property() + "' traverses more than one relation; not supported", method);
                    if (deleteRefs == predicateRefs) {
                        deleteRefs = new ArrayList<>(predicateRefs);
                    }
                    deletePaths = new ArrayList<>(predicatePaths);
                    deletePaths.set(i, null);
                    deleteRefs.set(i, PathResolver.placementOf(PathResolver.snakeCasedLocalColumnRef(input.predicates().get(i).property())));
                }
            }
            QueryDefinition.Criteria definition = publish(input,
                    new QueryPlan(ctx.entity(), input.predicates(), deleteRefs, deletePaths,
                            List.of(), List.of(), List.of(), List.of(), 0, input.paramBindings(), input.parameters()),
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

        private void emitFindRead(FindInput input) {
            List<SortSpec> methodSorts = queryPlanCompiler.sorts(method, input.parsed());
            for (SortSpec sort : methodSorts) {
                if (!sort.ignoreCase()) {
                    continue;
                }
                boolean paged = input.kind() == ResultKind.PAGE || input.kind() == ResultKind.CURSORED_PAGE;
                boolean toMany = pathResolver.resolveCompilePath(ctx.entity(), sort.property()).existential();
                if (paged || toMany) {
                    diagnostics.error(
                            input.name() + ": IgnoreCase ordering is not supported for "
                                    + (paged ? "paged reads" : "a to-many sort path ('" + sort.property() + "')"),
                            method);
                    return;
                }
            }
            if (input.kind() == ResultKind.CURSORED_PAGE || input.kind() == ResultKind.PAGE) {
                emitPagedFind(input, methodSorts);
            } else {
                emitPlainFind(input, methodSorts);
            }
        }

        private void emitPagedFind(FindInput input, List<SortSpec> methodSorts) {
            String pageRequest = queryPlanCompiler.specialParameter(input.parameters(), input.parameterTypes(), QueryPlanBuilder.PAGE_REQUEST);
            if (pageRequest == null) {
                diagnostics.error(
                        input.name() + " returning " + (input.kind() == ResultKind.PAGE ? "Page" : "CursoredPage")
                                + " requires a PageRequest parameter", method);
                return;
            }
            if (!joinedProjections.collectTraversedReferences(ctx.entity(), input.predicateRefs(), List.of()).isEmpty()) {
                diagnostics.note(
                        input.name() + ": full materialisation through joins is not supported for paged finders;"
                                + " association fields materialize as id-only partials", method);
            }
            List<PathResolution> pagedSortPaths = methodSorts.stream()
                    .map(s -> pathResolver.resolveCompilePath(ctx.entity(), s.property()))
                    .toList();
            List<ColumnPlacement> pagedSortRefs = new ArrayList<>();
            List<Boolean> pagedExistentialSorts = new ArrayList<>();
            List<String> pagedSortAttributePaths = new ArrayList<>();
            for (int i = 0; i < pagedSortPaths.size(); i++) {
                ColumnPlacement ref = pagedSortPaths.get(i).placement();
                pagedSortRefs.add(ref == null
                        ? PathResolver.placementOf(PathResolver.snakeCasedLocalColumnRef(methodSorts.get(i).property())) : ref);
                pagedExistentialSorts.add(ref != null && pagedSortPaths.get(i).existential());
                pagedSortAttributePaths.add(ref != null ? pagedSortPaths.get(i).attributePath() : null);
            }
            QueryDefinition.Criteria definition = publish(input,
                    new QueryPlan(ctx.entity(), input.predicates(), input.predicateRefs(), input.predicateAttributePaths(),
                            methodSorts, pagedSortRefs, pagedSortAttributePaths, pagedExistentialSorts, 0,
                            input.paramBindings(), input.parameters()),
                    input.distinct(), Operation.FIND, input.kind(), input.kind());
            String constant = SqlConstants.register(ctx.constants(), "SQL_" + MemberNames.upperSnake(input.name()),
                    new SqlBuilder(ctx.entity(), ctx.entity(), definition).dialect(sqlDialect).select());
            pagingEmitter.emitPaged(builder, ctx, new PagedRead(method, pageRequest, methodSorts, constant,
                    input.paramBindings(), input.parameters(), input.kind() == ResultKind.CURSORED_PAGE));
        }

        private void emitPlainFind(FindInput input, List<SortSpec> methodSorts) {
            PathOrigin sortOrigin = (input.parsed() != null || method.getAnnotationsByType(OrderBy.class).length > 0)
                    ? PathOrigin.DECLARED : PathOrigin.INFERRED;
            List<PathResolution> sortPaths = pathResolver.resolveCompilePaths(ctx.entity(),
                    methodSorts.stream().map(SortSpec::property).toList(), method, sortOrigin);
            if (sortOrigin.failsOnUnresolved() && sortPaths.stream().anyMatch(p -> p.placement() == null)) {
                return;
            }
            List<ColumnPlacement> sortRefs = sortPaths.stream().map(PathResolution::placement).toList();
            List<Boolean> existentialSorts = sortPaths.stream().map(PathResolution::existential).toList();
            List<String> sortAttributePaths = sortPaths.stream().map(PathResolution::attributePath).toList();

            List<Prop.Reference> traversed = joinedProjections.collectTraversedReferences(ctx.entity(), input.predicateRefs(), sortRefs);
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
                    new QueryPlan(ctx.entity(), input.predicates(), input.predicateRefs(), input.predicateAttributePaths(),
                            methodSorts, sortRefs, sortAttributePaths, existentialSorts,
                            input.parsed() != null ? ((io.github.fludakit.data.qbn.ParsedMethodName) input.parsed()).limit() : 0, input.paramBindings(), input.parameters()),
                    input.distinct(), Operation.FIND, input.kind(), input.kind());
            String sql = new SqlBuilder(ctx.entity(), ctx.entity(), queryDefinition)
                    .dialect(sqlDialect).select(extraProjection);
            String constant = SqlConstants.register(ctx.constants(), "SQL_" + MemberNames.upperSnake(input.name()), sql);
            CodeBlock.Builder call = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
            QueryInvocations.appendBoundParameters(call, input.paramBindings(), input.parameters());
            emitFindTerminal(input, call, queryArgForMethod);
        }

        private void emitFindTerminal(FindInput input, CodeBlock.Builder call, CodeBlock queryArgForMethod) {
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
