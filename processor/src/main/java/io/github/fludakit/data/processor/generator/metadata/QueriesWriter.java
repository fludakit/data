package io.github.fludakit.data.processor.generator.metadata;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.processor.generator.RepositorySourceWriter;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.QueryDefinition;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.Map;

/**
 * Writes the {@code <Repository>Queries} holder — the one generated artifact nothing at runtime reads
 * (see {@link #write}). It is a writer of its own rather than a second method on
 * {@link RepositoryShellEmitter} because the two artifacts share nothing but the package they land in: the
 * implementation class is built from a method list and a constant map, this one from the per-repository
 * map of {@link QueryDefinition}s collected in the session registry, and neither can drift toward the
 * other's shape.
 */
public final class QueriesWriter {

    // the query-metadata types the generated <Repository>Queries serialises literals with.
    private static final ClassName Q_QUERY_DEFINITION =
            ClassName.get("io.github.fludakit.data.query", "QueryDefinition");
    private static final ClassName Q_RESULT_KIND =
            ClassName.get("io.github.fludakit.data.query", "ResultKind");
    private static final ClassName Q_PAGING_MODE =
            ClassName.get("io.github.fludakit.data.query", "PagingMode");
    private static final ClassName Q_LIMIT_SPEC =
            ClassName.get("io.github.fludakit.data.query", "LimitSpec");
    private static final ClassName Q_PARAM_BINDING =
            ClassName.get("io.github.fludakit.data.query", "ParamBinding");
    private static final ClassName Q_LIKE_PATTERN =
            ClassName.get("io.github.fludakit.data.query", "LikePattern");

    private final RepositorySourceWriter sourceWriter;

    public QueriesWriter(RepositorySourceWriter sourceWriter) {
        this.sourceWriter = sourceWriter;
    }

    /**
     * Emits the generated query-metadata holder: {@code public final class <Repository>Queries} with one
     * {@code public static final QueryDefinition <FIELD>} per query-by-names method. The map is the live
     * view collected by the {@code RepositoryMethodCompiler} for this repository — its insertion order is
     * the declaration order of the methods, and the field name is the {@code UPPER_SNAKE} form of the
     * generated key. Each field is a record literal built over logical query records, so the published
     * structure describes the plan rendered into the SQL constant in {@code <Repository>Impl} —
     * introspectable without reflection, and re-emitted per build so it can never drift.
     */
    public void write(TypeElement repository, String packageName, String queriesSimpleName,
               Map<String, QueryDefinition.Criteria> queryDefinitions) {
        TypeSpec.Builder holder = TypeSpec.classBuilder(queriesSimpleName)
                .addJavadoc("Generated query definitions for {@code $L}: one {@code QueryDefinition} field per"
                        + " query-by-names method. The rendered SQL literals live in {@code $LImpl};"
                        + " this holder publishes logical paths and query semantics used to render them."
                        + " Generated — do not edit.\n",
                        queriesSimpleName, queriesSimpleName.substring(0,
                                queriesSimpleName.length() - "Queries".length()))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);
        holder.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        for (Map.Entry<String, QueryDefinition.Criteria> entry : queryDefinitions.entrySet()) {
            holder.addField(queryDefinitionField(entry.getKey(), entry.getValue()));
        }
        sourceWriter.write(packageName + "." + queriesSimpleName, repository,
                JavaFile.builder(packageName, holder.build()).skipJavaLangImports(true).build());
    }

    /**
     * One {@code public static final QueryDefinition <FIELD> = new QueryDefinition.<variant>(...)}
     * literal. Each variant emits only the components it carries, so a published count has no sort
     * list, no row window and no seek terms — not empty ones.
     */
    private FieldSpec queryDefinitionField(String typeName, QueryDefinition.Criteria definition) {
        CodeBlock predicates = Literals.listLiteral(builder -> {
            for (int i = 0; i < definition.predicates().size(); i++) {
                if (i > 0) {
                    builder.add(", ");
                }
                builder.add(Literals.predicateLiteral(definition.predicates().get(i)));
            }
        });
        CodeBlock params = Literals.listLiteral(builder -> {
            for (int i = 0; i < definition.paramBindings().size(); i++) {
                if (i > 0) {
                    builder.add(", ");
                }
                ParamBinding binding = definition.paramBindings().get(i);
                builder.add("new $T($L, $S, $T.$L)", Q_PARAM_BINDING, binding.argIndex(), binding.paramName(),
                        Q_LIKE_PATTERN, binding.pattern().name());
            }
        });
        CodeBlock initializer = switch (definition) {
            case QueryDefinition.Read read -> CodeBlock.builder()
                    .add("new $T.Read($T.$L, $L, $L, $T.$L, new $T($L, $L), $L, $L, $L)",
                            Q_QUERY_DEFINITION,
                            Q_RESULT_KIND, read.resultKind().name(),
                            predicates, Literals.sortListLiteral(read.sorts()),
                            Q_PAGING_MODE, read.paging().name(),
                            Q_LIMIT_SPEC, read.limit().limit(), read.limit().offset(),
                            Literals.seekListLiteral(read.seekTerms()), params,
                            read.distinct())
                    .build();
            case QueryDefinition.Count count -> CodeBlock.builder()
                    .add("new $T.Count($L, $L, $L)", Q_QUERY_DEFINITION, predicates, params, count.distinct())
                    .build();
            case QueryDefinition.Exists ignored -> CodeBlock.builder()
                    .add("new $T.Exists($L, $L)", Q_QUERY_DEFINITION, predicates, params)
                    .build();
            case QueryDefinition.DeleteWhere delete -> CodeBlock.builder()
                    .add("new $T.DeleteWhere($L, $L, $T.$L)", Q_QUERY_DEFINITION, predicates, params,
                            Q_RESULT_KIND, delete.resultKind().name())
                    .build();
        };
        return FieldSpec.builder(Q_QUERY_DEFINITION, MemberNames.upperSnake(typeName),
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer(initializer).build();
    }
}
