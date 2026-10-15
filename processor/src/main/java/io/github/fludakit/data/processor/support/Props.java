package io.github.fludakit.data.processor.support;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.TypeMapping.Prop;

/**
 * Queries about a {@link Prop property}: its physical column, its relationship target type.
 *
 * <p>These are the facts the metadata descriptor, the static metamodel and the row mapper all agree
 * on before any of them emits anything — a column spelled one way in the descriptor and another in
 * the mapper is a class that does not compile, so the spelling lives in one place. Every method here
 * is a pure function of its arguments: nothing reads the annotation-processing environment.</p>
 *
 * <p>Separated from {@link MemberNames} because these methods answer questions about <em>property
 * storage</em> (column name, target type), not about <em>member naming</em> (getter, setter,
 * capitalization). The two concerns change for different reasons.</p>
 */
public final class Props {

    private Props() {
    }

    /**
     * The property's own physical column (or fk column), {@code null} for a columnless container.
     */
    public static String column(Prop prop) {
        if (prop instanceof Prop.Id id) {
            return id.column();
        }
        if (prop instanceof Prop.Scalar scalar) {
            return scalar.column();
        }
        if (prop instanceof Prop.Embedded embedded) {
            return Names.columnBase(embedded.name());
        }
        if (prop instanceof Prop.Reference reference) {
            return reference.fkColumn();
        }
        return null;
    }

    /**
     * The FQN of the type {@code prop} points at, or {@code null} when the property is a plain scalar.
     * An {@link Prop.Embedded} answers its descriptor's type; the relationship kinds answer the
     * target (or child element) type they were declared with.
     */
    public static String targetFqn(Prop prop) {
        if (prop instanceof Prop.Embedded embedded) {
            return embedded.target() == null ? null : embedded.target().typeName();
        }
        if (prop instanceof Prop.Reference reference) {
            return reference.targetTypeName();
        }
        if (prop instanceof Prop.EntityCollection entityCollection) {
            return entityCollection.targetTypeName();
        }
        if (prop instanceof Prop.ElementCollection elementCollection) {
            return elementCollection.elementTypeName();
        }
        return null;
    }
}
