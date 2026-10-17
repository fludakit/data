package io.github.fludakit.data.processor.generator.method;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.analysis.TypeModelBuilder;
import io.github.fludakit.data.processor.analysis.QueryPlanBuilder;
import io.github.fludakit.data.processor.generator.QueryInvocations;
import io.github.fludakit.data.processor.generator.RepositoryEmission;
import io.github.fludakit.data.processor.generator.SqlConstants;
import io.github.fludakit.data.processor.support.GeneratedArtifactRegistry;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import jakarta.data.repository.Param;
import jakarta.data.repository.Query;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * The {@code @Query} shape: a native SQL statement the application wrote, bound with the method's
 * parameters and executed with the terminal that matches the declared return shape.
 */
public final class NativeQueryGenerator implements MethodGenerator {

    private final QueryPlanBuilder queryPlanCompiler;
    private final TypeModelBuilder typeMappingBuilder;
    private final MethodResults methodResults;
    private final GeneratedArtifactRegistry registry;

    public NativeQueryGenerator(QueryPlanBuilder queryPlanCompiler, TypeModelBuilder typeMappingBuilder,
                         MethodResults methodResults, GeneratedArtifactRegistry registry) {
        this.queryPlanCompiler = queryPlanCompiler;
        this.typeMappingBuilder = typeMappingBuilder;
        this.methodResults = methodResults;
        this.registry = registry;
    }

    @Override
    public boolean generate(MethodSpec.Builder builder, ExecutableElement method, ExecutableType resolved,
                            RepositoryEmission emission) {
        Query query = method.getAnnotation(Query.class);
        if (query == null) {
            return false;
        }
        emitQuery(builder, method, query, resolved.getReturnType(), emission);
        return true;
    }

    private void emitQuery(MethodSpec.Builder builder, ExecutableElement method, Query query, TypeMirror returnMirror,
                           RepositoryEmission emission) {
        TypeMapping<?> entity = emission.entity();
        String name = method.getSimpleName().toString();
        String constant = SqlConstants.register(emission.constants(), "SQL_" + MemberNames.upperSnake(name), query.value());
        CodeBlock.Builder sql = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        List<? extends VariableElement> parameters = method.getParameters();
        boolean positional = SqlConstants.hasPositionalParameter(query.value());
        for (int i = 0; i < parameters.size(); i++) {
            VariableElement parameter = parameters.get(i);
            if (queryPlanCompiler.isSpecial(parameter.asType())) {
                continue;
            }
            if (positional) {
                sql.add(".param($L)", MemberNames.parameterName(parameter, "arg" + i));
            } else {
                Param param = parameter.getAnnotation(Param.class);
                String bindingName = param != null ? param.value() : MemberNames.parameterName(parameter, "arg" + i);
                sql.add(".param($S, $L)", bindingName, MemberNames.parameterName(parameter, "arg" + i));
            }
        }
        ResultKind kind = methodResults.resultKindOf(returnMirror);
        QueryDefinitions.publish(registry, emission, name, new QueryDefinition.Native(query.value(), kind));
        TypeName queryType = methodResults.queryResultType(returnMirror, typeMappingBuilder.classNameFor(entity.typeName()));
        if (returnMirror.getKind() == TypeKind.ARRAY) {
            sql.add(".query($T.class).list().toArray($T[]::new)", queryType, queryType);
            builder.addStatement("return $L", sql.build());
        } else if (kind == ResultKind.VOID) {
            QueryInvocations.terminal(sql, kind, CodeBlock.of("$T.class", queryType));
            builder.addStatement("$L", sql.build());
        } else {
            QueryInvocations.terminal(sql, kind, CodeBlock.of("$T.class", queryType));
            builder.addStatement("return $L", sql.build());
        }
    }
}
