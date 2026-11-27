package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.CodeBlock;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.query.LikePattern;
import io.github.fludakit.data.query.ParamBinding;

import javax.lang.model.element.VariableElement;

/**
 * Shared by the emitters: appends the {@code .param(...)} chain for a method's
 * {@link MethodContext#paramBindings() parameter bindings} to a {@code JdbcClient} call.
 */
final class ParamBindingCode {

    private ParamBindingCode() {
    }

    /** Appends one {@code .param(name, value)} line per binding, applying the binding's LIKE wildcards. */
    static void append(CodeBlock.Builder body, MethodContext method) {
        for (ParamBinding binding : method.paramBindings()) {
            VariableElement param = method.parameters().get(binding.argIndex());
            String paramName = param.getSimpleName().toString();
            LikePattern pattern = binding.pattern();

            if (pattern.wildcarded()) {
                String prefix = pattern.prefix();
                String suffix = pattern.suffix();
                if (!prefix.isEmpty() && !suffix.isEmpty()) {
                    body.add("    .param($S, $S + $L + $S)\n", binding.paramName(), "%", paramName, "%");
                } else if (!prefix.isEmpty()) {
                    body.add("    .param($S, $S + $L)\n", binding.paramName(), "%", paramName);
                } else {
                    body.add("    .param($S, $L + $S)\n", binding.paramName(), paramName, "%");
                }
            } else {
                body.add("    .param($S, $L)\n", binding.paramName(), paramName);
            }
        }
    }
}
