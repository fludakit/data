package io.github.fludakit.data.processor;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.processor.support.Diagnostics;

import javax.annotation.processing.Filer;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.util.Map;

/**
 * Writes the generated {@code Repository_} skeleton for a resolved repository: the JDBC client
 * field, the SQL constants, the generated member methods, and the optional {@code JdbcClient} accessor.
 *
 * <p>Started by the host — {@link RepositoryProcessor} — once a repository has been resolved, so the
 * visitor stays a pure resolution step and the writing of generated artifacts is an emission concern.
 */
final class RepositoryEmitter {

    private final TypeElement repository;
    private final RepositoryContext ctx;
    private final ExecutableElement jdbcClientAccessor;
    private final Filer filer;
    private final Diagnostics diagnostics;

    RepositoryEmitter(TypeElement repository, RepositoryContext ctx, ExecutableElement jdbcClientAccessor,
                       Filer filer, Diagnostics diagnostics) {
        this.repository = repository;
        this.ctx = ctx;
        this.jdbcClientAccessor = jdbcClientAccessor;
        this.filer = filer;
        this.diagnostics = diagnostics;
    }

    void emit() {
        String repoSimpleName = repository.getSimpleName().toString();
        ClassName repoClassName = ClassName.get(repository);
        ClassName generatedName = ClassName.get(
                repoClassName.packageName(),
                repoSimpleName + "_");

        ClassName jdbcClientClass = ClassName.bestGuess("io.github.fludakit.jdbc.JdbcClient");
        ClassName applicationScoped = ClassName.bestGuess("jakarta.enterprise.context.ApplicationScoped");
        ClassName inject = ClassName.bestGuess("jakarta.inject.Inject");

        TypeSpec.Builder shell = TypeSpec.classBuilder(generatedName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(applicationScoped)
                .addSuperinterface(repoClassName)
                .addField(FieldSpec.builder(jdbcClientClass, "jdbcClient",
                                Modifier.PRIVATE, Modifier.FINAL)
                        .build())
                .addMethod(MethodSpec.constructorBuilder()
                        .addAnnotation(inject)
                        .addParameter(jdbcClientClass, "jdbcClient")
                        .addStatement("this.jdbcClient = jdbcClient")
                        .build());

        // Add SQL constants as public static final fields
        for (Map.Entry<String, String> entry : ctx.constants().entrySet()) {
            shell.addField(FieldSpec.builder(String.class, entry.getKey(),
                            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$S", entry.getValue())
                    .build());
        }

        // Add generated method overrides
        for (MethodSpec auxMethod : ctx.auxMethods()) {
            shell.addMethod(auxMethod);
        }

        // Add the JdbcClient accessor override, when the repository declares one
        if (jdbcClientAccessor != null) {
            shell.addMethod(MethodSpec.overriding(jdbcClientAccessor)
                    .addStatement("return this.$L", "jdbcClient")
                    .build());
        }

        try {
            JavaFile.builder(repoClassName.packageName(), shell.build())
                    .build()
                    .writeTo(filer);
        } catch (IOException e) {
            diagnostics.error("Failed to write " + generatedName + ": " + e.getMessage(), repository);
        }
    }
}
