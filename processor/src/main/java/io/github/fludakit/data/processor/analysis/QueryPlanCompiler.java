package io.github.fludakit.data.processor.analysis;

import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.RepositoryProcessor;
import io.github.fludakit.data.processor.model.Binding;
import io.github.fludakit.data.processor.model.QueryDefinitionSpec;
import io.github.fludakit.data.processor.model.QueryPlan;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.qbn.ParsedMethodName;
import io.github.fludakit.data.qbn.QueryByMethodNameParser;
import io.github.fludakit.data.query.LikePattern;
import io.github.fludakit.data.query.LimitSpec;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SeekTerm;
import io.github.fludakit.data.query.SortSpec;
import io.github.fludakit.data.sql.SqlBuilder;
import jakarta.data.repository.By;
import jakarta.data.repository.OrderBy;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns ONE repository method declaration into the plan behind its SQL: the parsed method name, the
 * {@code @By}/{@code @OrderBy} annotations, the argument bindings, the {@link Predicate}s and
 * {@link SortSpec}s they become, the keyset seek terms a cursor uses, and the published
 * {@link QueryDefinitionSpec} that carries all of it into {@code <Repository>Queries}.
 *
 * <p>This is the only place the method NAME is interpreted. Two vocabularies feed it and they must
 * not mix: a query-by-method-name name is parsed, while an {@code @Find}/{@code @Delete} method is
 * annotation-driven and its name is left alone — reading the name of an annotated method would let a
 * {@code Like} suffix silently override an {@code @By}.</p>
 *
 * <p>The plan is a value: nothing here writes source, claims a type name, or touches
 * {@code ProcessingEnvironment}. It holds no {@link Types} either — the erasure tests that recognise
 * the paging parameters ({@code PageRequest}, {@code Sort}, {@code Limit}, {@code Order}), which are
 * positional rather than named, are {@link TypeShapes#erasedName}, so the one compile-time query this
 * compiler makes is not one it has to be able to perform.</p>
 */
public final class QueryPlanCompiler {

    public static final String PAGE_REQUEST = "jakarta.data.page.PageRequest";
    public static final String ORDER = "jakarta.data.Order";
    public static final String LIMIT = "jakarta.data.Limit";
    public static final String SORT = "jakarta.data.Sort";

    private final TypeShapes typeShapes;

    public QueryPlanCompiler(TypeShapes typeShapes) {
        this.typeShapes = typeShapes;
    }

    public String specialParameter(List<? extends VariableElement> parameters,
                                    List<? extends TypeMirror> parameterTypes, String erasedType) {
        for (int i = 0; i < parameters.size(); i++) {
            if (erasedType.equals(typeShapes.erasedName(parameterTypes.get(i)))) {
                return parameters.get(i).getSimpleName().toString();
            }
        }
        return null;
    }

    public List<Binding> bindings(List<? extends VariableElement> parameters, List<? extends TypeMirror> parameterTypes,
                                   boolean explicitFind, ParsedMethodName parsed) {
        List<Integer> indices = nonSpecialIndices(parameterTypes);
        List<Binding> result = new ArrayList<>();
        boolean annotated = parameters.stream().anyMatch(p -> p.getAnnotation(By.class) != null);
        if (annotated) {
            // @Find/@By declare the predicate, so the METHOD NAME is not resolved at all: @By names
            // the property and, carrying no operator, implies equality. Reading the name here would
            // let a `Like` suffix override the annotation — findByEmailLike(@By("email")) used to
            // bind a LIKE, so the same annotation produced different SQL depending on the method name.
            int cursor = 0;
            for (int index : indices) {
                By by = parameters.get(index).getAnnotation(By.class);
                if (by == null && !explicitFind) {
                    cursor++;
                    continue;
                }
                String property = by == null ? parameters.get(index).getSimpleName().toString()
                        : By.ID.equals(by.value()) ? MemberNames.ID_PROPERTY : by.value();
                result.add(new Binding(property, Operator.EQ, takeIndices(indices, cursor, Operator.EQ)));
                cursor += SqlBuilder.parameterCount(Operator.EQ);
            }
        } else if (explicitFind) {
            if (parsed != null) {
                addParsedBindings(result, parsed.predicates(), indices);
            } else {
                for (int index : indices) {
                    String property = parameters.get(index).getSimpleName().toString();
                    result.add(new Binding(property, Operator.EQ, index));
                }
            }
        } else if (parsed != null) {
            addParsedBindings(result, parsed.predicates(), indices);
        }
        return result;
    }

    /**
     * Allocates method arguments to the parsed predicates in order, giving each operator the number of
     * arguments {@link SqlBuilder#parameterCount} says it renders tokens for — so {@code BETWEEN}
     * takes the two bounds and the nullary operators take none (they used to consume an argument they
     * never bound, shifting every later predicate onto the wrong parameter).
     */
    private static void addParsedBindings(List<Binding> result, List<Predicate> predicates,
                                          List<Integer> indices) {
        int cursor = 0;
        for (Predicate predicate : predicates) {
            result.add(new Binding(predicate.property(), predicate.operator(),
                    takeIndices(indices, cursor, predicate.operator()),
                    predicate.connector(), predicate.ignoreCase()));
            cursor += SqlBuilder.parameterCount(predicate.operator());
        }
    }

    /** The {@code count} argument slots starting at {@code cursor}, falling back to raw positions. */
    private static List<Integer> takeIndices(List<Integer> indices, int cursor, Operator operator) {
        int count = SqlBuilder.parameterCount(operator);
        List<Integer> taken = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int at = cursor + i;
            taken.add(at < indices.size() ? indices.get(at) : at);
        }
        return taken;
    }

    /**
     * Converts the resolved parameter bindings into {@link Predicate}s whose named parameters are the
     * method's parameter names, so the SQL rendered by {@link SqlBuilder} uses exactly the tokens
     * bound by {@link RepositoryProcessor#appendBoundParameters}.
     */
    public List<Predicate> predicates(List<Binding> bindings, List<? extends VariableElement> parameters) {
        List<Predicate> predicates = new ArrayList<>();
        for (Binding binding : bindings) {
            // A nullary operator (Null/True/False) binds nothing, so there is no argument to name the
            // token after; the token is never rendered, so the property name is a safe placeholder.
            String param = binding.paramIndices().isEmpty()
                    ? binding.property()
                    : parameters.get(binding.paramIndices().get(0)).getSimpleName().toString();
            predicates.add(Predicate.of(binding.property(), binding.operator(), param,
                    binding.connector(), binding.ignoreCase()));
        }
        return predicates;
    }

    public List<SortSpec> sorts(ExecutableElement method, ParsedMethodName parsed) {
        List<SortSpec> sorts = new ArrayList<>();
        if (parsed != null) {
            sorts.addAll(parsed.sorts());
        }
        for (OrderBy orderBy : method.getAnnotationsByType(OrderBy.class)) {
            String property = By.ID.equals(orderBy.value()) ? MemberNames.ID_PROPERTY : orderBy.value();
            sorts.add(SortSpec.of(property, !orderBy.descending(), orderBy.ignoreCase()));
        }
        return sorts;
    }

    public ParsedMethodName tryParse(String name) {
        // A qbn-free prefix test: fluda-data-qbn is an OPTIONAL dependency, so the name is classified
        // before any qbn class is touched. RepositoryMethodValidator reports the diagnostics.
        if (!RepositoryMethodValidator.isQueryMethodName(name)) {
            return null;
        }
        try {
            return new QueryByMethodNameParser().parse(name);
        } catch (RuntimeException | LinkageError e) {
            // Unparseable name, or fluda-data-qbn absent from the annotation processor path.
            // RepositoryMethodValidator reports the diagnostic; there is nothing to emit.
            return null;
        }
    }

    public List<Integer> nonSpecialIndices(List<? extends TypeMirror> parameterTypes) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < parameterTypes.size(); i++) {
            if (!isSpecial(parameterTypes.get(i))) {
                indices.add(i);
            }
        }
        return indices;
    }

    public boolean isSpecial(TypeMirror type) {
        String erased = typeShapes.erasedName(type);
        if (PAGE_REQUEST.equals(erased) || ORDER.equals(erased) || LIMIT.equals(erased) || SORT.equals(erased)) {
            return true;
        }
        if (type.getKind() == TypeKind.ARRAY) {
            TypeMirror component = ((ArrayType) type).getComponentType();
            return SORT.equals(typeShapes.erasedName(component));
        }
        return false;
    }

    /**
     * Fold one query-by-names method's resolved lists into a {@link QueryDefinitionSpec}. The paging
     * mode and result kind come from the return {@link ResultKind} plus the parsed action; seek terms are
     * the runtime cursor key for CURSOR and the published default key for the other paged modes
     * (E1's base constant drops sorts/limit at render time, so the
     * declared sorts stay in the model for introspection). {@code paramBindings} are exactly the
     * {@code .param($S, ...)} tokens {@link RepositoryProcessor#appendBoundParameters} emits, keyed by argument slot.
     */
    public static QueryDefinitionSpec queryDefinitionOf(String typeName, Operation operation,
                                                  ResultKind kind, ResultKind resultKind,
                                                  QueryPlan plan, boolean distinct) {
        List<Predicate> logicalPredicates = new ArrayList<>();
        for (int i = 0; i < plan.predicates().size(); i++) {
            Predicate predicate = plan.predicates().get(i);
            // The connector and the case flag are part of the plan, not incidental: dropping them here
            // would publish a definition that renders differently from the baked SQL.
            String authoritative = plan.predicateAttributePaths().get(i);
            logicalPredicates.add(Predicate.of(
                    authoritative != null ? authoritative
                            : resolvedAttributePath(plan.mapping(), plan.predicateRefs().get(i), predicate.property()),
                    predicate.operator(), predicate.param(), predicate.connector(), predicate.ignoreCase()));
        }
        List<SortSpec> logicalSorts = new ArrayList<>(plan.sorts());
        for (int i = 0; i < plan.sorts().size(); i++) {
            SortSpec sort = plan.sorts().get(i);
            String sortPath = plan.sortAttributePaths().get(i);
            logicalSorts.set(i, SortSpec.of(
                    sortPath != null ? sortPath
                            : resolvedAttributePath(plan.mapping(), plan.sortRefs().get(i), sort.property()),
                    sort.ascending(), sort.ignoreCase()));

        }
        PagingMode paging = kind == ResultKind.PAGE ? PagingMode.OFFSET
                : kind == ResultKind.CURSORED_PAGE ? PagingMode.CURSOR : PagingMode.NONE;
        // Every paged shape (OFFSET as well as CURSOR) publishes the keyset key: the runtime emitPaged
        // wiring computes the same props/dirs lists for both, and SqlBuilder.build ignores seek terms
        // (paging != NONE renders the base constant), so this changes no rendered SQL.
        List<SeekTerm> seekTerms = paging == PagingMode.NONE ? List.of()
                : seekTerms(plan.mapping(), plan.sortRefs(), plan.existentialSorts(), logicalSorts);
        List<ParamBinding> paramBindings = new ArrayList<>();
        for (Binding binding : plan.bindings()) {
            for (int paramIndex : binding.paramIndices()) {
                // The LIKE decoration rides on the binding, so the published definition says HOW the
                // argument is bound — not just which argument. A reader of <Repository>Queries can
                // then tell a Contains (BOTH) from a StartsWith (TRAILING) whose SQL text is identical.
                paramBindings.add(new ParamBinding(paramIndex,
                        plan.parameters().get(paramIndex).getSimpleName().toString(),
                        LikePattern.of(binding.operator())));
            }
        }
        // Each variant takes only what it can use, so a published count carries no plan.sorts(), no row
        // window and no seek terms — not empty ones. A count and an exists know their own result
        // shape; a read and a delete-by-criteria take the one the method declares.
        QueryDefinition.Criteria definition = switch (operation) {
            case COUNT -> new QueryDefinition.Count(logicalPredicates, paramBindings, distinct);
            case EXISTS -> new QueryDefinition.Exists(logicalPredicates, paramBindings);
            case DELETE -> new QueryDefinition.DeleteWhere(logicalPredicates, paramBindings, resultKind);
            default -> new QueryDefinition.Read(resultKind, logicalPredicates, logicalSorts,
                    paging, new LimitSpec(plan.limit(), 0), seekTerms, paramBindings, distinct);
        };
        return new QueryDefinitionSpec(typeName, definition);
    }

    /**
     * The logical attribute path a resolved ref should be PUBLISHED under.
     *
     * <p>A local leaf is recovered by matching it back against the descriptor's own column index, so
     * the published model names properties the way the entity declares them. A relation-traversing
     * leaf has no local column to match, so the only available name is the caller's fallback — which
     * for a query-by-method-name property is the CONCATENATED, snake-cased token
     * ({@code order_items_quantity}). {@code SqlBuilder} resolves a definition's properties against the
     * descriptor with {@code visit}, which speaks ENTITY paths ({@code orderItems.quantity}), so a
     * concatenated token must be mapped back onto the attribute path that produced it — otherwise the
     * walk fails and the predicate degrades to a column literally named {@code order_items_quantity},
     * losing both the join and the EXISTS semi-join.
     */
    private static String resolvedAttributePath(TypeMapping<?> mapping, ColumnPlacement placement, String fallback) {
        if (placement.local()) {
            for (Map.Entry<String, ColumnRef> entry : mapping.columnRefs().entrySet()) {
                if (entry.getValue().equals(placement.leaf())) return entry.getKey();
            }
        }
        return fallback;
    }

    /**
     * The keyset seek terms a cursor uses: the declared sorts (or the {@code id ASC} default when the
     * method declares none), plus the root-id tiebreak exactly when a to-many sort is present and the
     * last term is not already an ascending id — the same rule {@link RepositoryProcessor#emitPaged} applies to the
     * runtime {@code props}/{@code dirs} lists, kept here so the published model matches the wiring.
     */
    private static List<SeekTerm> seekTerms(TypeMapping<?> mapping, List<ColumnPlacement> sortRefs,
                                            List<Boolean> existentialSorts, List<SortSpec> sorts) {
        ColumnRef idRef = new ColumnRef.Simple(mapping.idColumn(), mapping.tableName());
        if (sorts.isEmpty()) {
            return List.of(new SeekTerm(idRef, true));
        }
        List<SeekTerm> out = new ArrayList<>();
        for (int i = 0; i < sorts.size(); i++) {
            out.add(new SeekTerm(sortRefs.get(i).leaf(), sorts.get(i).ascending()));
        }
        boolean toManySort = existentialSorts.stream().anyMatch(Boolean.TRUE::equals);
        SortSpec last = sorts.get(sorts.size() - 1);
        boolean lastIsIdAsc = mapping.id().name().equals(last.property()) && last.ascending();
        if (toManySort && !lastIsIdAsc) {
            out.add(new SeekTerm(idRef, true));
        }
        return out;
    }
}
