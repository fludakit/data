package io.github.fludakit.data.processor.generator;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.analysis.RepositoryFlavour;
import io.github.fludakit.data.processor.model.WriteCascade;
import java.util.List;
import java.util.Map;

/**
 * Everything the method compiler needs that is fixed for the WHOLE repository rather than for one
 * method: the entity being generated for, what the repository interface INHERITS, the SQL constant
 * registry the emission fills, the row-mapper type, whether reads hydrate, the JOIN-projection state,
 * the auxiliary methods the emitters append to, the repository's qualified name (the key the query
 * definitions are published under in the session registry), the de-duplicating name counts, and the
 * write cascade.
 *
 * <p>One record instead of twelve parameters because they are always passed together and ten of the
 * twelve are never rebound: a call site that forgot one would compile as long as the TYPES still lined
 * up — {@code hydrate} and {@code crud} were both {@code boolean}, and so are several of the lists.
 * Bundling them makes a wrong order a compile error instead of a silently mis-generated repository.</p>
 *
 * <p>{@code flavour} is here because it answers a question about the INTERFACE, not about one method,
 * and it used to be re-decided for every method of the repository — two supertype walks per method to
 * reach an answer that cannot change between them. It is the enum rather than the
 * {@code boolean extendsCrud} it replaced so that "does this repository inherit the spec's write
 * lifecycle names?" stays an accessor on the answer ({@link RepositoryFlavour#crud()}) instead of a
 * second argument on every method signature that needed it.</p>
 *
 * <p>Query definitions are NOT a field here: they are session-scoped and live in
 * {@link io.github.fludakit.data.processor.support.GeneratedArtifactRegistry}, keyed by
 * {@code repositoryFqn}. This record only carries the key and the per-repository overload-name counter
 * the compiler needs while it emits.</p>
 *
 * <p>{@link EmissionContext} is its READ-side projection: {@link #readContext} is the only place that
 * pairing is spelled out, so the CRUD read emitters cannot drift from what {@code generateMethod}
 * built.</p>
 */
public record RepositoryEmission(TypeMapping<?> entity, RepositoryFlavour flavour, Map<String, String> constants,
                          ClassName mapperType, boolean hydrate, String crudFullMapper,
                          List<Prop.Reference> joinedReferences, List<MethodSpec> auxMethods,
                          String repositoryFqn, Map<String, Integer> queryDefinitionNames,
                          WriteCascade cascade) {

    /** The read-side subset: what {@link CrudReadEmitter} answers every inherited read from. */
    public EmissionContext readContext() {
        return new EmissionContext(entity, constants, mapperType, hydrate, crudFullMapper, joinedReferences);
    }
}
