package io.github.fludakit.data.processor.generator.nav;

import com.squareup.javapoet.TypeName;
import javax.lang.model.element.TypeElement;
import java.util.Set;

/**
 * The inputs fixed for a whole navigable-class generation rather than for one member of one class:
 * the entity a diagnostic is reported against, the package every generated class goes into, the owner
 * type every member's attribute type is parameterised by, and the set of nav FQNs already claimed.
 *
 * <p>One record because all four are constant for the entire recursion — an embedded value object
 * nesting another descends without changing any of them — and because three of them are
 * {@code String}s or a {@code TypeName} that a call site could transpose and still compile. The
 * per-class values (the {@code TypeSpec.Builder} and its {@code taken} set) are deliberately NOT in
 * here: they change at every level, and holding them would let a nested nav inherit its parent's
 * dedupe set.</p>
 */
public record NavContext(TypeElement origin, String packageName, TypeName owner, Set<String> claimed) {
}
