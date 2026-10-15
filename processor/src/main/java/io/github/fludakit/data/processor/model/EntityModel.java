package io.github.fludakit.data.processor.model;

import com.squareup.javapoet.ClassName;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;

import javax.lang.model.type.TypeMirror;
import java.util.List;

/**
 * The compile-time view of one mapped type: what the annotation processor resolved before any
 * emitter runs.
 *
 * <p>It is deliberately separate from the {@link TypeMapping} this processor emits, and the reason
 * is a DEPENDENCY one, not a lifecycle one: at generation time both records are compile-time data.
 * What differs is where each one ends up. {@code TypeMapping} is printed verbatim into the generated
 * {@code <Entity>Metadata} — {@code new Prop.Scalar("amount", "amount", "java.math.BigDecimal")} —
 * which ships in the user's jar and is then instantiated by the application. Every leaf of it must
 * therefore be a Java literal free of compile-time-only dependencies: no {@code javax.lang.model},
 * and no JavaPoet, which is a processor-only dependency and would otherwise become a runtime
 * dependency of every application built on this library.
 *
 * <p>None of what an emitter needs may violate that, so it all lives here instead: the erased
 * {@code ClassName} that drives import-managed {@code $T} emission, the {@code TypeMirror} for
 * {@code Types} queries, and the flattened {@link Property} list whose
 * {@link Property#embeddedModel()} reaches an embedded target's own compile-time model. Emitters
 * consume this rather than re-reading the element tree, which is what keeps them off
 * {@code ProcessingEnvironment}.
 *
 * @param typeMirror  the declared type, kept so emitters can ask the {@code Types} utility about it
 * @param typeName    the erased {@code ClassName} generated code refers to. A {@code ClassName} rather than a
 *                   {@code TypeName} because it always IS one (a mapped type is declared), and because it
 *                   then knows its own package — the answer six emitters used to get from {@code Elements}
 * @param id          the identifier property, or {@code null} for a value object
 * @param properties  every mapped non-id property, in emission order
 * @param metadata    the shipping descriptor this model was resolved from; it is the one that gets
 *                    printed into {@code <Entity>Metadata} and read back at runtime
 */
public record EntityModel(TypeMirror typeMirror, ClassName typeName, Property id,
                   List<Property> properties, TypeMapping<?> metadata) {

    /**
     * The {@code List<@ValueObject>} element collections, in declaration order — DERIVED from
     * {@link #metadata()} rather than carried as a component.
     *
     * <p>They used to be a second list of parallel {@code ElementCollection} records, one per field,
     * built alongside the descriptor. Everything they exposed is on the
     * {@link Prop.ElementCollection} prop already — the field name, the child table, the parent FK
     * and the placed value object — and the one fact that is not (the element's JavaPoet type) is
     * recoverable from the element's FQN by {@code AttributeTypes.classArgOf}. Carrying the list
     * meant the same field was modeled in two places that could disagree.</p>
     */
    public List<Prop.ElementCollection> collections() {
        return metadata.props().stream()
                .filter(Prop.ElementCollection.class::isInstance)
                .map(Prop.ElementCollection.class::cast)
                .toList();
    }
}
