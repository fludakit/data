package io.github.fludakit.data.processor.model;

import com.squareup.javapoet.ClassName;
import io.github.fludakit.data.metadata.TypeMapping.Prop;

/**
 * A relationship carried into emission: either a READ hydration loader member (it cleared the
 * managed-target gate: attribute, target's compile-time model, generated row mapper — every such
 * instance is loader-emittable) or a WRITE cascade member from the entity-collection scan (the
 * plain-SQL child INSERT/DELETE uses only attribute/targetModel, so a cascade member may carry a
 * {@code null} {@code targetMapper} — the child's row mapper is irrelevant to writes).
 */
public record EntityRelationship(Prop attribute, EntityModel targetModel, ClassName targetMapper) { }
