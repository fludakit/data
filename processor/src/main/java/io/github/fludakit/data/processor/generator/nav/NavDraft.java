package io.github.fludakit.data.processor.generator.nav;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeSpec;

/**
 * A claimed nav-class name plus its builder, produced by {@link NavSupport#beginNav}. The leaves are
 * added to the builder between {@code begin} and {@link NavSupport#finishNav}; {@link #unit()} is the
 * finished class once the accessors have been appended.
 */
public record NavDraft(String fqn, String packageName, String navSimple, TypeSpec.Builder builder) {

    public NavUnit unit() {
        return new NavUnit(fqn, ClassName.get(packageName, navSimple),
                NavSupport.navFile(packageName, builder));
    }
}
