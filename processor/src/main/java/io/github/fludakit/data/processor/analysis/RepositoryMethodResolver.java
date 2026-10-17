package io.github.fludakit.data.processor.analysis;

import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.model.PathResolution;
import io.github.fludakit.data.processor.model.PathStrictness;
import io.github.fludakit.data.processor.model.ResolvedMethod;
import io.github.fludakit.data.processor.model.ResolvedPredicate;
import io.github.fludakit.data.processor.model.ResolvedSort;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;
import jakarta.data.repository.By;
import jakarta.data.repository.OrderBy;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves ONE repository method into its finder input: the parsed name, the argument bindings, the
 * predicates and sorts with their compile-time column refs, the result kind, and the collision-suffixed
 * query-definition name.
 *
 * <p>Split from {@code FinderEmitter} so the "what does this method ask" half is separate from the
 * "emit the implementation" half. The only generate-side decision left to the emitter is the JOIN
 * projection, which depends on whether a row mapper exists.
 */
public final class RepositoryMethodResolver {

    private final QueryPlanBuilder queryPlanCompiler;
    private final PathResolver pathResolver;
    private final MethodResults methodResults;
    private final Diagnostics diagnostics;

    /** One finder method's resolved input, keyed by repository FQN plus the method signature. */
    private final Map<String, ResolvedMethod> cache = new HashMap<>();

    public RepositoryMethodResolver(QueryPlanBuilder queryPlanCompiler, PathResolver pathResolver,
                                    MethodResults methodResults, Diagnostics diagnostics) {
        this.queryPlanCompiler = queryPlanCompiler;
        this.pathResolver = pathResolver;
        this.methodResults = methodResults;
        this.diagnostics = diagnostics;
    }

    /**
     * Resolves everything a find/count/delete emitter needs from one method.
     *
     * @return the resolved input, or {@code null} when a declared predicate or sort property does not
     *         resolve — the ERROR is already reported, so the method must be skipped
     */
    public ResolvedMethod resolveFindMethod(ExecutableElement method, ExecutableType resolved,
                                            TypeMapping<?> entity,
                                            boolean explicitFind, boolean explicitDelete, String repositoryFqn) {
        String key = repositoryFqn + "#" + method;
        ResolvedMethod cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        String name = method.getSimpleName().toString();
        List<? extends VariableElement> parameters = method.getParameters();
        Object parsed = (explicitFind || explicitDelete) ? null : queryPlanCompiler.tryParse(name);
        Operation operation = explicitDelete ? Operation.DELETE
                : explicitFind ? Operation.FIND
                : parsed != null ? ((io.github.fludakit.data.qbn.ParsedMethodName) parsed).operation() : Operation.FIND;
        boolean distinct = parsed != null && ((io.github.fludakit.data.qbn.ParsedMethodName) parsed).distinct()
                && (operation == Operation.FIND || operation == Operation.COUNT);
        List<? extends TypeMirror> parameterTypes = resolved.getParameterTypes();
        QueryPlanBuilder.ResolvedBindings resolvedBindings =
                queryPlanCompiler.resolvedBindings(parameters, parameterTypes, explicitFind || explicitDelete, parsed);
        List<Predicate> predicates = resolvedBindings.predicates();
        List<ParamBinding> paramBindings = resolvedBindings.paramBindings();
        TypeMirror returnMirror = resolved.getReturnType();
        ResultKind kind = methodResults.resultKindOf(returnMirror);
        boolean annotatedParams = parameters.stream().anyMatch(p -> p.getAnnotation(By.class) != null);
        PathStrictness strictness = (parsed != null || annotatedParams) ? PathStrictness.STRICT : PathStrictness.LENIENT;
        List<PathResolution> predicatePaths = pathResolver.resolveCompilePaths(entity,
                predicates.stream().map(Predicate::property).toList(), method, strictness);
        if (strictness.failsOnUnresolved() && predicatePaths.stream().anyMatch(p -> p.placement() == null)) {
            return null;
        }
        List<ResolvedPredicate> resolvedPredicates = new ArrayList<>();
        for (int i = 0; i < predicates.size(); i++) {
            resolvedPredicates.add(new ResolvedPredicate(predicates.get(i),
                    predicatePaths.get(i).placement(), predicatePaths.get(i).attributePath()));
        }
        List<SortSpec> sorts = queryPlanCompiler.sorts(method, parsed);
        List<ResolvedSort> resolvedSorts = resolveSorts(method, entity, sorts, kind, parsed);
        if (resolvedSorts == null) {
            return null;
        }
        ResolvedMethod result = new ResolvedMethod(name, parameters, parsed, operation, distinct, parameterTypes, paramBindings,
                returnMirror, kind, resolvedPredicates, sorts, resolvedSorts);
        cache.put(key, result);
        return result;
    }

    /**
     * The sorts with their compile-time column refs, or {@code null} when an ignore-case ordering is
     * unsupported or a strict sort path does not resolve (already reported).
     */
    private List<ResolvedSort> resolveSorts(ExecutableElement method, TypeMapping<?> entity,
                                            List<SortSpec> sorts, ResultKind kind, Object parsed) {
        for (SortSpec sort : sorts) {
            if (!sort.ignoreCase()) {
                continue;
            }
            boolean paged = kind == ResultKind.PAGE || kind == ResultKind.CURSORED_PAGE;
            boolean toMany = pathResolver.resolveCompilePath(entity, sort.property()).existential();
            if (paged || toMany) {
                diagnostics.error(
                        method.getSimpleName() + ": IgnoreCase ordering is not supported for "
                                + (paged ? "paged reads" : "a to-many sort path ('" + sort.property() + "')"),
                        method);
                return null;
            }
        }
        return kind == ResultKind.PAGE || kind == ResultKind.CURSORED_PAGE
                ? resolvePagedSorts(entity, sorts)
                : resolvePlainSorts(method, entity, sorts, parsed);
    }

    /** The lenient paged-sort resolution: an unresolved path degrades to a bare snake-cased column. */
    private List<ResolvedSort> resolvePagedSorts(TypeMapping<?> entity, List<SortSpec> sorts) {
        List<ResolvedSort> resolvedSorts = new ArrayList<>();
        for (SortSpec sort : sorts) {
            PathResolution pr = pathResolver.resolveCompilePath(entity, sort.property());
            ColumnPlacement original = pr.placement();
            ColumnPlacement ref = original == null
                    ? PathResolver.placementOf(PathResolver.snakeCasedLocalColumnRef(sort.property()))
                    : original;
            resolvedSorts.add(new ResolvedSort(sort, ref,
                    original == null ? null : pr.attributePath(), original != null && pr.existential()));
        }
        return resolvedSorts;
    }

    /** The strict plain-sort resolution: a strict unresolved sort path fails the whole method. */
    private List<ResolvedSort> resolvePlainSorts(ExecutableElement method, TypeMapping<?> entity,
                                                 List<SortSpec> sorts, Object parsed) {
        PathStrictness sortStrictness = (parsed != null || method.getAnnotationsByType(OrderBy.class).length > 0)
                ? PathStrictness.STRICT : PathStrictness.LENIENT;
        List<PathResolution> sortPaths = pathResolver.resolveCompilePaths(entity,
                sorts.stream().map(SortSpec::property).toList(), method, sortStrictness);
        if (sortStrictness.failsOnUnresolved() && sortPaths.stream().anyMatch(p -> p.placement() == null)) {
            return null;
        }
        List<ResolvedSort> resolvedSorts = new ArrayList<>();
        for (int i = 0; i < sorts.size(); i++) {
            resolvedSorts.add(new ResolvedSort(sorts.get(i), sortPaths.get(i).placement(),
                    sortPaths.get(i).attributePath(), sortPaths.get(i).existential()));
        }
        return resolvedSorts;
    }
}
