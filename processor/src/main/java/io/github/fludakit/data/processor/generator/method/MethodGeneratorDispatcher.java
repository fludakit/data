package io.github.fludakit.data.processor.generator.method;

import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.processor.generator.RepositoryEmission;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.ExecutableType;

/**
 * The ordered list of method generators: a method is handed to the first generator that accepts it, and
 * the order of the list IS the dispatch precedence.
 *
 * <p>The order reproduces the ten-shape priority the old {@code RepositoryMethodCompiler.compileBody}
 * encoded, but stated as a data list rather than an {@code if} chain. A reader sees the precedence by
 * reading the list top-down; adding a shape is appending (or inserting) one entry, not threading a new
 * branch through a method body.
 */
public final class MethodGeneratorDispatcher {

    private final List<MethodGenerator> generators;

    public MethodGeneratorDispatcher(List<MethodGenerator> generators) {
        this.generators = List.copyOf(generators);
    }

    public void generate(MethodSpec.Builder builder, ExecutableElement method, ExecutableType resolved,
                         RepositoryEmission emission) {
        for (MethodGenerator generator : generators) {
            if (generator.generate(builder, method, resolved, emission)) {
                return;
            }
        }
        throw new IllegalStateException("no method generator handles " + method);
    }
}
