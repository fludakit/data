package io.github.fludakit.data.processor.generator;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.sql.SqlDialect;
import io.github.fludakit.data.sql.SqlFormatter;
import io.github.fludakit.jdbc.JdbcClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.List;
import java.util.Map;

/**
 * Emits the generated repository implementation shell: {@code public class <Repository>Impl implements
 * <Repository>}, carrying the {@code SQL_DIALECT} constant, one {@code private static final String}
 * per SQL statement the method compiler registered, and the single injected {@code JdbcClient} every
 * generated method calls.
 *
 * <p>The shell is the whole class — the {@code MethodSpec}s handed in are the methods, so this is
 * where the per-repository {@code LinkedHashMap} of constants becomes the field list in declaration
 * order. Nothing here decides what a method does; it only assembles and writes.</p>
 */
public final class RepositoryShellEmitter {

    private final RepositorySourceWriter sourceWriter;
    /**
     * The {@code SqlDialect} implementation the generated class pins as its {@code SQL_DIALECT}.
     * Captured at construction (after {@code init} has read the {@code fluda.dialect} option), since
     * the option is a build-wide setting, not a per-repository one.
     */
    private final ClassName dialectClass;

    public RepositoryShellEmitter(RepositorySourceWriter sourceWriter, ClassName dialectClass) {
        this.sourceWriter = sourceWriter;
        this.dialectClass = dialectClass;
    }

    /**
     * The absolute indent (spaces) JavaPoet places before a class member. A text block body and its
     * closing delimiter are emitted through a single {@code $L} literal, which JavaPoet writes verbatim
     * and never re-wraps, so the base indent is baked into the text here rather than left to the
     * emitter — every content line and the closing {@code """} share it, and the compiler strips that
     * common margin from the runtime value, keeping only the relative indentation
     * {@link io.github.fludakit.data.sql.SqlFormatter} adds for subqueries and {@code SET} items.
     */
    private static final String TEXT_BLOCK_INDENT = "            ";

    /**
     * The field initializer for one SQL constant. The registry holds the RAW assembled statement (so
     * derived constants can splice onto it cleanly); the clause-per-line layout is applied here, once,
     * at the point the value becomes a literal written to the file. {@link SqlFormatter} reflows a whole
     * statement to multiple lines — rendered as a Java text block — and leaves a runtime splice fragment
     * (one whose boundary whitespace is load-bearing) byte-for-byte, which renders verbatim as a quoted
     * string so its leading or trailing space survives.
     */
    private static CodeBlock sqlInitializer(String sql) {
        String formatted = SqlFormatter.format(sql);
        return formatted.contains("\n") ? sqlTextBlock(formatted) : CodeBlock.of("$S", formatted);
    }

    /** Renders the already-formatted {@code sql} as a Java text block: {@code """} then one line per clause. */
    private static CodeBlock sqlTextBlock(String sql) {
        String[] lines = sql.split("\n", -1);
        StringBuilder body = new StringBuilder("\"\"\"");
        for (String line : lines) {
            body.append('\n').append(TEXT_BLOCK_INDENT).append(escapeTextBlockLine(line));
        }
        body.append('\n').append(TEXT_BLOCK_INDENT).append("\"\"\"");
        return CodeBlock.of("$L", body.toString());
    }

    /**
     * Escapes the two sequences a text block reads as more than they look: a backslash (SQL string
     * literals may carry one) and a run of three double quotes (which would close the block early). A
     * lone or paired double quote is left as-is — the delimiter never abuts a content line, since the
     * closing {@code """} sits on its own line.
     */
    private static String escapeTextBlockLine(String line) {
        return line.replace("\\", "\\\\").replace("\"\"\"", "\\\"\"\"");
    }

    public void write(TypeElement repository, String packageName, String implementationName,
               Map<String, String> constants, List<MethodSpec> methods) {
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(implementationName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(ApplicationScoped.class)
                .addSuperinterface(TypeName.get(repository.asType()));
        classBuilder.addField(FieldSpec.builder(SqlDialect.class, "SQL_DIALECT",
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.INSTANCE", dialectClass)
                .build());
        for (Map.Entry<String, String> constant : constants.entrySet()) {
            classBuilder.addField(FieldSpec.builder(String.class, constant.getKey(),
                            Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer(sqlInitializer(constant.getValue()))
                    .build());
        }
        // Named through MemberNames so the {@link JdbcClientAware} accessor the generator may emit below
        // returns THIS field: the two are separate emits over the same class.
        classBuilder.addField(FieldSpec.builder(JdbcClient.class, MemberNames.JDBC_CLIENT_FIELD, Modifier.PRIVATE)
                .addAnnotation(Inject.class)
                .build());
        methods.forEach(classBuilder::addMethod);

        sourceWriter.write(packageName + "." + implementationName, repository,
                JavaFile.builder(packageName, classBuilder.build()).skipJavaLangImports(true).build());
    }
}
