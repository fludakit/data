package io.github.fludakit.data.processor.support;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;

/**
 * Wraps a generated method body in a try/catch that translates RuntimeExceptions
 * to Jakarta Data DataException subtypes via DataExceptions.translate().
 */
public final class ExceptionTranslation {

    private static final ClassName DATA_EXCEPTIONS =
            ClassName.get("io.github.fludakit.data.support", "DataExceptions");

    private ExceptionTranslation() {}

    public static CodeBlock wrap(CodeBlock body) {
        return CodeBlock.builder()
                .add("try {\n")
                .indent()
                .add(body)
                .add("\n")
                .unindent()
                .add("} catch ($T e) {\n", RuntimeException.class)
                .indent()
                .add("throw $T.translate(e);\n", DATA_EXCEPTIONS)
                .unindent()
                .add("}\n")
                .build();
    }
}
