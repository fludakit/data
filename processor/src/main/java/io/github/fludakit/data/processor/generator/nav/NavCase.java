package io.github.fludakit.data.processor.generator.nav;

import com.squareup.javapoet.CodeBlock;
import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.TypeMapping;
import javax.lang.model.element.TypeElement;

/**
 * One navigated leaf of a mapped type: the full dot path, its resolved placement, and the target type
 * plus the accessor chain that {@code ValueAccess.value(...)} needs.
 *
 * <p>Shared by the descriptor's {@code resolve()} (which reads the placement) and its {@code value()}
 * switch (which reads the head/target/toOne), because the two surfaces enumerate the same paths and
 * must never disagree about which paths exist.
 */
public record NavCase(String path, ColumnPlacement placement, TypeElement targetElement,
                      TypeMapping<?> target, CodeBlock head, boolean toOne) {
}
