package io.github.fludakit.data.processor.analysis;

import com.squareup.javapoet.ClassName;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.metadata.TypeMapping;

/**
 * A relationship carried into emission: bundles the core metadata ({@link Prop} attribute,
 * {@link TypeMapping} target model) with the code-generation artifact (the target's row mapper
 * {@link ClassName}) needed for hydration.
 *
 * <p>Used for both READ hydration loader members and WRITE cascade members. For reads, every
 * instance is loader-emittable: the {@code targetMapper} is the generated row mapper. For writes,
 * the plain-SQL child INSERT/DELETE uses only {@code attribute} and {@code targetModel}, so the
 * {@code targetMapper} is {@code null} — the child's row mapper is irrelevant to writes.</p>
 */
public record EntityRelationship(Prop attribute, TypeMapping<?> targetModel, ClassName targetMapper) { }
