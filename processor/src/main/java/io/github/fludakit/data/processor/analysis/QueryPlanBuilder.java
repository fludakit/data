package io.github.fludakit.data.processor.analysis;

import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.query.LikePattern;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;
import io.github.fludakit.data.query.Connector;
import io.github.fludakit.data.sql.SqlBuilder;
import jakarta.data.repository.By;
import jakarta.data.repository.OrderBy;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns ONE repository method declaration into the plan behind its SQL: the parsed method name, the
 * {@code @By}/{@code @OrderBy} annotations, the argument bindings, and the {@link Predicate}s and
 * {@link SortSpec}s they become. Folding that plan into a {@code QueryDefinition} is
 * {@link PlanPublisher}'s job.
 *
 * <p>This is the only place the method NAME is interpreted. Two vocabularies feed it and they must
 * not mix: a query-by-method-name name is parsed, while an {@code @Find}/{@code @Delete} method is
 * annotation-driven and its name is left alone — reading the name of an annotated method would let a
 * {@code Like} suffix silently override an {@code @By}.</p>
 *
 * <p>The plan is a value: nothing here writes source, claims a type name, or touches
 * {@code ProcessingEnvironment}. It holds a raw {@link Types} for the erasure tests that recognise the
 * paging parameters ({@code PageRequest}, {@code Sort}, {@code Limit}, {@code Order}), which are
 * positional rather than named.</p>
 */
public final class QueryPlanBuilder {

    public static final String PAGE_REQUEST = "jakarta.data.page.PageRequest";
    public static final String ORDER = "jakarta.data.Order";
    public static final String LIMIT = "jakarta.data.Limit";
    public static final String SORT = "jakarta.data.Sort";

    private final Types types;

    public QueryPlanBuilder(Types types) {
        this.types = types;
    }

    public String specialParameter(List<? extends VariableElement> parameters,
                                    List<? extends TypeMirror> parameterTypes, String erasedType) {
        for (int i = 0; i < parameters.size(); i++) {
            if (erasedType.equals(types.erasure(parameterTypes.get(i)).toString())) {
                return parameters.get(i).getSimpleName().toString();
            }
        }
        return null;
    }

    /**
     * Resolves the method's parameter bindings into core {@link Predicate}s and {@link ParamBinding}s
     * directly, eliminating the intermediate {@code Binding} representation. The predicates carry
     * named parameters resolved from the method's {@link VariableElement}s; the param bindings expand
     * multi-argument operators (BETWEEN) into individual entries.
     */
    public ResolvedBindings resolvedBindings(List<? extends VariableElement> parameters,
                                              List<? extends TypeMirror> parameterTypes,
                                              boolean explicitFind, Object parsed) {
        List<Integer> indices = nonSpecialIndices(parameterTypes);
        List<Predicate> predicates = new ArrayList<>();
        List<ParamBinding> paramBindings = new ArrayList<>();
        boolean annotated = parameters.stream().anyMatch(p -> p.getAnnotation(By.class) != null);
        if (annotated) {
            int cursor = 0;
            for (int index : indices) {
                By by = parameters.get(index).getAnnotation(By.class);
                if (by == null && !explicitFind) {
                    cursor++;
                    continue;
                }
                String property = by == null ? parameters.get(index).getSimpleName().toString()
                        : By.ID.equals(by.value()) ? MemberNames.ID_PROPERTY : by.value();
                addResolvedBinding(predicates, paramBindings, parameters, indices, cursor,
                        property, Operator.EQ, Connector.AND, false);
                cursor += SqlBuilder.parameterCount(Operator.EQ);
            }
        } else if (explicitFind) {
            if (parsed != null) {
                addParsedResolvedBindings(predicates, paramBindings, parameters,
                        ((io.github.fludakit.data.qbn.ParsedMethodName) parsed).predicates(), indices);
            } else {
                for (int index : indices) {
                    String property = parameters.get(index).getSimpleName().toString();
                    addResolvedBinding(predicates, paramBindings, parameters, indices, 0,
                            property, Operator.EQ, Connector.AND, false);
                }
            }
        } else if (parsed != null) {
            addParsedResolvedBindings(predicates, paramBindings, parameters,
                    ((io.github.fludakit.data.qbn.ParsedMethodName) parsed).predicates(), indices);
        }
        return new ResolvedBindings(predicates, paramBindings);
    }

    /**
     * Bundles the resolved predicates and parameter bindings for one query method. Replaces the
     * intermediate {@code Binding} representation with direct use of core types.
     */
    public record ResolvedBindings(List<Predicate> predicates, List<ParamBinding> paramBindings) {}

    private static void addParsedResolvedBindings(List<Predicate> predicates, List<ParamBinding> paramBindings,
                                                   List<? extends VariableElement> parameters,
                                                   List<Predicate> parsed, List<Integer> indices) {
        int cursor = 0;
        for (Predicate predicate : parsed) {
            addResolvedBinding(predicates, paramBindings, parameters, indices, cursor,
                    predicate.property(), predicate.operator(), predicate.connector(), predicate.ignoreCase());
            cursor += SqlBuilder.parameterCount(predicate.operator());
        }
    }

    private static void addResolvedBinding(List<Predicate> predicates, List<ParamBinding> paramBindings,
                                            List<? extends VariableElement> parameters, List<Integer> indices,
                                            int cursor, String property, Operator operator,
                                            Connector connector, boolean ignoreCase) {
        int count = SqlBuilder.parameterCount(operator);
        List<Integer> taken = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int at = cursor + i;
            taken.add(at < indices.size() ? indices.get(at) : at);
        }
        String param = taken.isEmpty() ? property : parameters.get(taken.getFirst()).getSimpleName().toString();
        predicates.add(Predicate.of(property, operator, param, connector, ignoreCase));
        LikePattern pattern = LikePattern.of(operator);
        List<String> tokenNames = SqlBuilder.paramNames(operator, param);
        for (int i = 0; i < taken.size(); i++) {
            paramBindings.add(new ParamBinding(taken.get(i), tokenNames.get(i), pattern));
        }
    }

    public List<SortSpec> sorts(ExecutableElement method, Object parsed) {
        List<SortSpec> sorts = new ArrayList<>();
        if (parsed != null) {
            sorts.addAll(((io.github.fludakit.data.qbn.ParsedMethodName) parsed).sorts());
        }
        for (OrderBy orderBy : method.getAnnotationsByType(OrderBy.class)) {
            String property = By.ID.equals(orderBy.value()) ? MemberNames.ID_PROPERTY : orderBy.value();
            sorts.add(SortSpec.of(property, !orderBy.descending(), orderBy.ignoreCase()));
        }
        return sorts;
    }

    public Object tryParse(String name) {
        // A qbn-free prefix test: fluda-data-qbn is an OPTIONAL dependency, so the name is classified
        // before any qbn class is touched. RepositoryMethodValidator reports the diagnostics.
        return RepositoryMethodValidator.QbnSupport.isQueryMethodName(name)
                ? RepositoryMethodValidator.QbnSupport.parseOrNull(name) : null;
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
        String erased = types.erasure(type).toString();
        if (PAGE_REQUEST.equals(erased) || ORDER.equals(erased) || LIMIT.equals(erased) || SORT.equals(erased)) {
            return true;
        }
        if (type.getKind() == TypeKind.ARRAY) {
            TypeMirror component = ((ArrayType) type).getComponentType();
            return SORT.equals(types.erasure(component).toString());
        }
        return false;
    }
}
