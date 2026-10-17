package io.github.fludakit.data.processor.generator.method;

import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.processor.generator.RepositoryContext;
import jakarta.data.repository.Delete;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.ExecutableType;

/**
 * The query-by-method-name fallthrough: the last shape, so any method that reached it is a finder whose
 * name IS the query. Delegates to {@link FinderEmitter} with {@code explicitFind=false}; a
 * parameter-derived {@code @Delete} keeps its {@code explicitDelete} flag.
 */
public final class QueryByMethodNameGenerator implements MethodGenerator {

    private final FinderEmitter finderEmitter;

    public QueryByMethodNameGenerator(FinderEmitter finderEmitter) {
        this.finderEmitter = finderEmitter;
    }

    @Override
    public boolean generate(MethodSpec.Builder builder, ExecutableElement method, ExecutableType resolved,
                            RepositoryContext emission) {
        finderEmitter.emit(builder, method, resolved, emission, false, method.getAnnotation(Delete.class) != null);
        return true;
    }
}
