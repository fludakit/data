package io.github.fludakit.data.processor.model;

import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.List;

/**
 * The resolved input for one finder method: the parsed action, the argument bindings, the predicates
 * and sorts with their compile-time column refs, and the collision-suffixed query-definition name.
 *
 * <p>Produced by {@code RepositoryMethodResolver} and consumed by the finder emitter, so the "what does
 * this method ask" half of a finder is separate from the "emit the implementation" half.
 */
public record ResolvedMethod(String name, List<? extends VariableElement> parameters, Object parsed,
                             Operation operation, boolean distinct, List<? extends TypeMirror> parameterTypes,
                             List<ParamBinding> paramBindings, TypeMirror returnMirror, ResultKind kind,
                             List<ResolvedPredicate> predicates, List<SortSpec> sorts, List<ResolvedSort> resolvedSorts) {
}
