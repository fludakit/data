package io.github.fludakit.data.processor.generator.method;

import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.processor.generator.RepositoryContext;
import jakarta.data.repository.Find;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.ExecutableType;

/**
 * The {@code @Find} shape: an explicit finder whose criteria come from {@code @By} (or from parameter
 * names when {@code @By} is absent) and whose name is never analysed.
 */
public final class FindMethodGenerator implements MethodGenerator {

    private final FinderEmitter finderEmitter;

    public FindMethodGenerator(FinderEmitter finderEmitter) {
        this.finderEmitter = finderEmitter;
    }

    @Override
    public boolean generate(MethodSpec.Builder builder, ExecutableElement method, ExecutableType resolved,
                            RepositoryContext emission) {
        if (method.getAnnotation(Find.class) == null) {
            return false;
        }
        finderEmitter.emit(builder, method, resolved, emission, true, false);
        return true;
    }
}
