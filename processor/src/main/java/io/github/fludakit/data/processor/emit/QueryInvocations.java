package io.github.fludakit.data.processor.emit;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import io.github.fludakit.data.processor.model.Binding;
import io.github.fludakit.data.processor.model.EntityModel;
import io.github.fludakit.data.query.LikePattern;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.sql.SqlBuilder;

import javax.lang.model.element.VariableElement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * How a compiled query is turned into the {@code jdbcClient} call that runs it: the bound parameters,
 * the terminal that matches the declared return shape, the row-mapper argument and the hydration hook.
 *
 * <p>Stateless on purpose, and the reason it exists as its own home is that the call shapes are
 * shared: the paged reads ({@link PagingEmitter}) and the query-by-names / {@code @Find} reads in
 * {@link RepositoryMethodCompiler} emit the same {@code .param(...)} chain and the same
 * {@code .query(mapper).list()} terminal. One copy is what keeps a paged read and a find read from
 * binding a {@code LIKE} parameter with different wildcards, or from disagreeing on which terminal a
 * declared {@code Set} needs.</p>
 */
public final class QueryInvocations {

    public static void appendBoundParameters(CodeBlock.Builder call, List<Binding> bindings,
                                       List<? extends VariableElement> parameters) {
        for (Binding binding : bindings) {
            Operator operator = binding.operator();
            // A nullary operator renders no token and has no argument to bind, so there is no name to
            // derive; skip before touching the (empty) argument list.
            if (binding.paramIndices().isEmpty()) {
                continue;
            }
            String param = parameters.get(binding.paramIndices().get(0)).getSimpleName().toString();
            List<String> names = SqlBuilder.paramNames(operator, param);
            for (int i = 0; i < names.size(); i++) {
                String value = parameters.get(binding.paramIndices().get(i)).getSimpleName().toString();
                call.add(".param($S, $L)", names.get(i), boundValue(operator, value));
            }
        }
    }

    /**
     * The value a predicate binds: the argument, decorated with the {@code %} wildcards the operator's
     * {@link LikePattern} asks for. The rule itself lives on {@link LikePattern#of} — this only
     * renders it, so the code generator holds no second copy that could disagree with the model.
     * {@link LikePattern#NONE} emits the bare argument: {@code Like} binds the caller's own pattern.
     */
    public static CodeBlock boundValue(Operator operator, String param) {
        LikePattern pattern = LikePattern.of(operator);
        return switch (pattern) {
            case BOTH -> CodeBlock.of("$S + $L + $S", pattern.prefix(), param, pattern.suffix());
            case LEADING -> CodeBlock.of("$S + $L", pattern.prefix(), param);
            case TRAILING -> CodeBlock.of("$L + $S", param, pattern.suffix());
            case NONE -> CodeBlock.of("$L", param);
        };
    }

    public static void terminal(CodeBlock.Builder call, ResultKind kind, CodeBlock queryArg) {
        switch (kind) {
            case LIST -> call.add(".query($L).list()", queryArg);
            case SET -> call.add(".query($L).list()", queryArg);
            case STREAM -> call.add(".query($L).stream()", queryArg);
            case OPTIONAL -> call.add(".query($L).optional()", queryArg);
            case VOID -> call.add(".update()");
            case SINGLE -> call.add(".query($L).single()", queryArg);
        }
        if (kind == ResultKind.SET) {
            // The client materializes a List; widen it to the declared Set, preserving row order.
            call.add(".stream().collect($T.toCollection($T::new))", Collectors.class, LinkedHashSet.class);
        }
    }

    /**
     * Returns the argument passed to {@code JdbcClient.query(...)}: an instance of the compile-time
     * generated {@code <Entity>RowMapper} when one exists, otherwise the entity class for the
     * reflective mapper.
     */
    public static CodeBlock queryArg(EntityModel entity, ClassName mapperType) {
        return mapperType != null
                ? CodeBlock.of("new $T()", mapperType)
                : CodeBlock.of("$T.class", entity.typeName());
    }

    /**
     * The single hydration hook every read shape calls once its parents are materialized: the umbrella
     * {@code hydrateAll} dispatches to each collection loader. {@code parentsExpr} is either a
     * materialized local (a list of parents) or an inline {@code java.util.List.of(...)} for the
     * optional / single cases. Keeping the wrapping in one place is what lets every read shape share
     * the same hydration wiring.
     */
    public static CodeBlock hydrationCall(String parentsExpr) {
        return CodeBlock.of("hydrateAll($L)", parentsExpr);
    }
}
