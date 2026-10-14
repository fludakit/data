package io.github.fludakit.data.processor.emit;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.sql.SqlDialect;
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
                    .initializer("$S", constant.getValue())
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
