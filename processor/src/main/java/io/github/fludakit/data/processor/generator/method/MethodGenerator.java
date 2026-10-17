package io.github.fludakit.data.processor.generator.method;

import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.processor.generator.RepositoryEmission;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.ExecutableType;

/**
 * One shape of repository method, recognised and emitted independently of every other shape.
 *
 * <p>This is the processor's analogue of Micronaut Data's finders: instead of one class carrying a
 * ten-branch dispatch, each shape is a small class whose {@link #generate} either handles the method
 * and returns {@code true}, or declines and returns {@code false}. {@link MethodGeneratorDispatcher}
 * walks an ordered list and stops at the first generator that accepts the method, which is exactly the
 * precedence the old single switch encoded — but each shape is now readable, and testable, on its own.
 *
 * <p>A generator is built by the wiring with the collaborators it needs and nothing else; the method
 * receives only the facts that vary per method (the declaration, its resolved type, and the
 * per-repository emission it writes into).
 */
public interface MethodGenerator {

    /**
     * Emits the implementation of {@code method} into {@code builder}, returning {@code true} when this
     * generator owns the method's shape (so dispatch stops) and {@code false} to hand it to the next
     * generator in the list.
     */
    boolean generate(MethodSpec.Builder builder, ExecutableElement method, ExecutableType resolved,
                     RepositoryEmission emission);
}
