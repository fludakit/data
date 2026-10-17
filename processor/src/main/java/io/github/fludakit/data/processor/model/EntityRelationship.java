package io.github.fludakit.data.processor.model;

import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.metadata.TypeMapping;

/**
 * A relationship carried into emission: bundles the core metadata ({@link Prop} attribute,
 * {@link TypeMapping} target model) with the code-generation artifact (the FQN of the target's
 * generated row mapper) needed for hydration.
 *
 * <p>Used for both READ hydration loader members and WRITE cascade members. For reads, every
 * instance is loader-emittable: the {@code targetMapper} is the generated row mapper's FQN. For
 * writes, the plain-SQL child INSERT/DELETE uses only {@code attribute} and {@code targetModel}, so
 * the {@code targetMapper} is {@code null} — the child's row mapper is irrelevant to writes.</p>
 *
 * <p>The mapper is carried as an FQN {@code String}, not a JavaPoet {@code ClassName}, so this model
 * package stays free of JavaPoet; an emitter resolves it back with {@code ClassName.bestGuess(...)}
 * only when it emits the loader.</p>
 */
public record EntityRelationship(Prop attribute, TypeMapping<?> targetModel, String targetMapper) {
}
