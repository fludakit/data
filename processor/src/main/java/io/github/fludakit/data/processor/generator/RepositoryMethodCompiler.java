package io.github.fludakit.data.processor.generator;

import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeVariableName;
import io.github.fludakit.data.processor.analysis.TypeModelBuilder;
import io.github.fludakit.data.processor.analysis.PathResolver;
import io.github.fludakit.data.processor.analysis.QueryPlanBuilder;
import io.github.fludakit.data.processor.analysis.TypeShapes;
import io.github.fludakit.data.processor.generator.metadata.TypeMappingGenerator;
import io.github.fludakit.data.processor.generator.method.CrudReadMethodGenerator;
import io.github.fludakit.data.processor.generator.method.CrudWriteMethodGenerator;
import io.github.fludakit.data.processor.generator.method.FindMethodGenerator;
import io.github.fludakit.data.processor.generator.method.FinderEmitter;
import io.github.fludakit.data.processor.generator.method.MethodGeneratorDispatcher;
import io.github.fludakit.data.processor.generator.method.MethodResults;
import io.github.fludakit.data.processor.generator.method.NativeQueryGenerator;
import io.github.fludakit.data.processor.generator.method.QueryByMethodNameGenerator;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.support.GeneratedArtifactRegistry;
import io.github.fludakit.data.sql.SqlDialect;
import io.github.fludakit.data.support.DataExceptions;
import io.github.fludakit.jdbc.JdbcClientException;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;

/**
 * Compiles one abstract repository method into its {@code MethodSpec}, delegating the per-shape body to
 * the ordered {@link MethodGeneratorDispatcher}.
 *
 * <p>The one thing every shape shares is the shell this class owns: the resolved signature, the
 * overriding {@code MethodSpec.Builder} with its type variables rebound, and the {@code try}/{@code
 * catch} that translates a {@code JdbcClientException} into a Jakarta Data exception. The ten shapes
 * that used to live here now live in {@code generate/method}, one class per shape.
 */
public final class RepositoryMethodCompiler {

    private final TypeShapes typeShapes;
    private final TypeModelBuilder typeMappingBuilder;
    private final MethodGeneratorDispatcher dispatcher;

    public RepositoryMethodCompiler(TypeShapes typeShapes, TypeModelBuilder typeMappingBuilder,
                             Diagnostics diagnostics, SqlDialect sqlDialect, PathResolver pathResolver,
                             QueryPlanBuilder queryPlanCompiler, TypeMappingGenerator metadataGenerator,
                             WriteMethodEmitter writeMethodEmitter,
                             JoinedProjectionEmitter joinedProjections,
                             GeneratedArtifactRegistry registry) {
        this.typeShapes = typeShapes;
        this.typeMappingBuilder = typeMappingBuilder;
        MethodResults methodResults = new MethodResults(typeShapes);
        CrudReadEmitter crudReads = new CrudReadEmitter(sqlDialect, typeMappingBuilder, metadataGenerator, joinedProjections);
        PagingEmitter pagingEmitter = new PagingEmitter(sqlDialect, typeMappingBuilder, pathResolver, metadataGenerator, diagnostics);
        FinderEmitter finderEmitter = new FinderEmitter(typeMappingBuilder, diagnostics, sqlDialect, pathResolver,
                queryPlanCompiler, joinedProjections, pagingEmitter, registry, methodResults);
        this.dispatcher = new MethodGeneratorDispatcher(List.of(
                new NativeQueryGenerator(queryPlanCompiler, typeMappingBuilder, methodResults, registry),
                new CrudReadMethodGenerator(crudReads, methodResults, registry),
                new FindMethodGenerator(finderEmitter),
                new CrudWriteMethodGenerator(writeMethodEmitter, crudReads, methodResults, typeMappingBuilder, registry),
                new QueryByMethodNameGenerator(finderEmitter)));
    }

    public MethodSpec generateMethod(ExecutableElement method, DeclaredType repositoryType,
                              RepositoryContext emission) {
        ExecutableType resolved = typeShapes.resolved(repositoryType, method);
        MethodSpec.Builder builder = typeShapes.overriding(method, repositoryType);
        rebindTypeVariables(builder, method, typeMappingBuilder.classNameFor(emission.entity().typeName()));
        // One guard per method rather than per call site: every jdbcClient invocation lives inside the
        // body, so a single catch translates each failure the client raises without relabeling an
        // unrelated bug (a NPE in a row mapper or a hydrate step) as a data exception.
        builder.beginControlFlow("try");
        dispatcher.generate(builder, method, resolved, emission);
        builder.nextControlFlow("catch ($T e)", JdbcClientException.class);
        builder.addStatement("throw $T.translate(e)", DataExceptions.class);
        builder.endControlFlow();
        return builder.build();
    }

    private void rebindTypeVariables(MethodSpec.Builder builder, ExecutableElement method, TypeName entityType) {
        if (!builder.typeVariables.isEmpty() && !method.getTypeParameters().isEmpty()) {
            builder.typeVariables.clear();
            builder.typeVariables.add(TypeVariableName.get(
                    method.getTypeParameters().get(0).getSimpleName().toString(), entityType));
        }
    }
}
