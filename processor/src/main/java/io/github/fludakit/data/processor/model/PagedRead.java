package io.github.fludakit.data.processor.model;

import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.SortSpec;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import java.util.List;

/**
 * The per-METHOD inputs of a paged read: the method being compiled, the parameter the caller's
 * {@code PageRequest} arrives in, the sorts the method declares, the base constant the page is cut
 * from, the parameter bindings and the method parameters they bind, and whether the declared page is
 * a cursor page.
 *
 * <p>{@code method} is in here — and not passed beside this record — because a paged read is the one
 * shape that can REJECT a declared sort property, and a diagnostic has to be reported against the
 * declaration that carries it. Without it the rejection could only be thrown, which is what made an
 * unresolvable sort surface as a generic "could not generate" on the whole repository.</p>
 *
 * <p>The rest of what {@link PagingEmitter} needs — the entity, the constant registry, the row mapper
 * and the hydration flag — is fixed for the whole repository, so it is passed separately rather than
 * bundled here: this record is the part that changes from one method to the next.</p>
 */
public record PagedRead(ExecutableElement method, String pageRequest, List<SortSpec> sorts, String findConstant,
                 List<ParamBinding> paramBindings, List<? extends VariableElement> parameters, boolean cursored) {
}
