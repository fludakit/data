package io.github.fludakit.data.processor.generator.method;

import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.processor.analysis.MethodResults;
import io.github.fludakit.data.processor.generator.CrudReadEmitter;
import io.github.fludakit.data.processor.generator.RepositoryContext;
import io.github.fludakit.data.processor.GeneratedArtifacts;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.query.LimitSpec;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SeekTerm;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;

/**
 * The inherited CRUD read shapes that precede the finder: the paged {@code findAll(PageRequest, Sort)}
 * and, when composing to-ones are JOIN-projected, the projected {@code findById}/{@code findAll}.
 *
 * <p>The plain {@code findById}/{@code findAll} built-ins that run when there is no JOIN projection are
 * handled later, by {@link CrudWriteMethodGenerator}, exactly as the original dispatch ordered them —
 * the join-projected variants must win ahead of the finder, while the plain ones must lose to an
 * {@code @Find} annotation.
 */
public final class CrudReadMethodGenerator implements MethodGenerator {

    private final CrudReadEmitter crudReads;
    private final MethodResults methodResults;
    private final GeneratedArtifacts registry;

    public CrudReadMethodGenerator(CrudReadEmitter crudReads, MethodResults methodResults,
                            GeneratedArtifacts registry) {
        this.crudReads = crudReads;
        this.methodResults = methodResults;
        this.registry = registry;
    }

    @Override
    public boolean generate(MethodSpec.Builder builder, ExecutableElement method, ExecutableType resolved,
                            RepositoryContext emission) {
        String name = method.getSimpleName().toString();
        List<? extends VariableElement> parameters = method.getParameters();
        int arity = parameters.size();
        TypeMirror returnMirror = resolved.getReturnType();
        ResultKind kind = methodResults.resultKindOf(returnMirror);

        if (name.equals("findAll") && arity == 2) {
            QueryDefinitions.publish(registry, emission, name, findAllPage(emission, kind));
            crudReads.emitFindAllPage(builder, parameters, emission);
            return true;
        }
        if (emission.crudFullMapper() != null) {
            if (arity == 1 && name.equals("findById")) {
                QueryDefinitions.publish(registry, emission, name, findById(emission, kind));
                crudReads.emitFindById(builder, MemberNames.parameterName(parameters.getFirst(), MemberNames.ID_PARAM),
                        emission, kind);
                return true;
            }
            if (arity == 0 && name.equals("findAll")) {
                QueryDefinitions.publish(registry, emission, name, findAll(kind));
                crudReads.emitFindAll(builder, emission, kind);
                return true;
            }
        }
        return false;
    }

    static QueryDefinition.Read findById(RepositoryContext emission, ResultKind kind) {
        return new QueryDefinition.Read(kind,
                List.of(Predicate.of(emission.entity().id().name(), Operator.EQ, MemberNames.ID_PARAM)),
                List.of(), PagingMode.NONE, LimitSpec.none(), List.of(),
                List.of(new ParamBinding(0, MemberNames.ID_PARAM)), false);
    }

    static QueryDefinition.Read findAll(ResultKind kind) {
        return new QueryDefinition.Read(kind, List.of(), List.of(), PagingMode.NONE, LimitSpec.none(),
                List.of(), List.of(), false);
    }

    private QueryDefinition.Read findAllPage(RepositoryContext emission, ResultKind kind) {
        return new QueryDefinition.Read(kind, List.of(), List.of(), PagingMode.OFFSET, LimitSpec.none(),
                List.of(new SeekTerm(new ColumnRef.Simple(emission.entity().idColumn(), emission.entity().tableName()), true)),
                List.of(), false);
    }
}
