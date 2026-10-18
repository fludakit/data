package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.query.LikePattern;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.sql.H2Dialect;
import io.github.fludakit.data.sql.SqlBuilder;
import io.github.fludakit.data.sql.SqlDialect;

import javax.lang.model.element.VariableElement;
import java.util.List;

/**
 * Generates method implementations for plain (non-paged) finder methods.
 *
 * <p>A plain finder has {@code Operation.FIND} and {@code PagingMode.NONE}. The emitter builds
 * the SQL SELECT at compile time, registers it as a static constant in the repository context,
 * and generates a method body that chains {@code JdbcClient} calls:</p>
 *
 * <pre>{@code
 * @Override
 * public List<Customer> findByName(String name) {
 *     return jdbcClient
 *         .sql(FIND_BY_NAME_SQL)
 *         .param("name", name)
 *         .query(new CustomerRowMapper())
 *         .list();
 * }
 * }</pre>
 *
 * <p>Result kind handling:</p>
 * <ul>
 *   <li>{@code SINGLE} &rarr; {@code .single()}</li>
 *   <li>{@code OPTIONAL} &rarr; {@code .optional()}</li>
 *   <li>{@code LIST} &rarr; {@code .list()}</li>
 *   <li>{@code ARRAY} &rarr; {@code .list().toArray(new E[0])}</li>
 *   <li>{@code STREAM} &rarr; {@code .list().stream()}</li>
 *   <li>{@code SET} &rarr; {@code .list().stream().collect(Collectors.toSet())}</li>
 * </ul>
 */
public final class FindEmitter {

    private final SqlDialect dialect;

    public FindEmitter(SqlDialect dialect) {
        this.dialect = dialect;
    }

    /** Default constructor using H2 dialect. */
    public FindEmitter() {
        this(H2Dialect.INSTANCE);
    }

    /**
     * Generates a method override for a plain finder.
     *
     * @return the method spec, or {@code null} if the method is not a plain finder
     */
    public MethodSpec emit(RepositoryContext ctx, MethodContext method) {
        if (method.operation() != Operation.FIND || method.pagingMode() != PagingMode.NONE) {
            return null;
        }

        MethodSpec.Builder builder = MethodSpec.overriding(method.method());

        // Build SQL at compile time
        String sql = buildSql(ctx, method);

        // Register as a static constant
        String constBase = toSqlConstantName(method.name());
        String constName = ctx.registerConstant(constBase, sql);

        // Build method body
        addMethodBody(builder, ctx, method, constName);

        return builder.build();
    }

    // ── SQL building ─────────────────────────────────────────────────────

    private String buildSql(RepositoryContext ctx, MethodContext method) {
        SqlBuilder sqlBuilder = new SqlBuilder(ctx.entity());
        sqlBuilder.dialect(dialect);
        return sqlBuilder.selectBy(
                method.predicates(),
                method.sorts(),
                0,
                null
        );
    }

    // ── Method body generation ───────────────────────────────────────────

    private void addMethodBody(MethodSpec.Builder builder, RepositoryContext ctx,
                                MethodContext method, String sqlConstantName) {
        ClassName rowMapperType = ctx.rowMapperType();
        List<ParamBinding> bindings = method.paramBindings();

        // Build the fluent JdbcClient chain as a single return statement
        CodeBlock.Builder body = CodeBlock.builder();

        // Start: return jdbcClient
        body.add("return $L\n", "jdbcClient");

        // .sql(SQL_CONSTANT)
        body.add("    .sql($L)\n", sqlConstantName);

        // .param(...) for each binding
        for (ParamBinding binding : bindings) {
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

        // .query(new XxxRowMapper())
        body.add("    .query(new $T())\n", rowMapperType);

        // Terminal operation
        emitTerminal(body, method.resultKind(), ctx);
        body.add(";");

        builder.addStatement(body.build());
    }

    /**
     * Appends the terminal operation call to the code block.
     */
    private void emitTerminal(CodeBlock.Builder body, ResultKind resultKind, RepositoryContext ctx) {
        switch (resultKind) {
            case SINGLE -> body.add(".single()");
            case OPTIONAL -> body.add(".optional()");
            case LIST -> body.add(".list()");
            case ARRAY -> {
                String entityFqn = ctx.entity().typeName();
                ClassName entityType = ClassName.bestGuess(entityFqn);
                body.add(".list().toArray(new $T[0])", entityType);
            }
            case STREAM -> body.add(".list().stream()");
            case SET -> body.add(".list().stream().collect($T.toSet())",
                    ClassName.get("java.util.stream", "Collectors"));
            default -> body.add(".list()");
        }
    }

    // ── Naming utilities ─────────────────────────────────────────────────

    /**
     * Converts a method name to a SQL constant name base.
     * E.g. {@code findByName} → {@code FIND_BY_NAME_SQL}.
     */
    static String toSqlConstantName(String methodName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < methodName.length(); i++) {
            char c = methodName.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                char prev = methodName.charAt(i - 1);
                if (Character.isLowerCase(prev)) {
                    sb.append('_');
                } else if (i + 1 < methodName.length()
                        && Character.isLowerCase(methodName.charAt(i + 1))) {
                    sb.append('_');
                }
            }
            sb.append(Character.toUpperCase(c));
        }
        sb.append("_SQL");
        return sb.toString();
    }
}
