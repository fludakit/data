package io.github.fludakit.data.processor.model;

import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.metadata.ElementCollectionValueObjectMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;

/**
 * A {@code List<@ValueObject>} element collection.
 *
 * <p>Only {@code valueObjectType} is something the descriptor cannot carry. The field name, the child
 * table, the parent FK and the value object's own descriptor are all DERIVED from the
 * {@link Prop.ElementCollection} — this record used to be handed the descriptor a second time as
 * its own component, so the same object was stored under two names and a reader had to decide
 * whether {@code descriptor()} and {@code element().target()} could ever differ.
 *
 * @param valueObjectType the element type of the list, as a JavaPoet {@code TypeName}; the
 *                        descriptor carries the same type as an FQN {@code String}
 * @param element         the {@link Prop.ElementCollection} that is also this collection's entry in
 *                        the parent's props — the single source of truth for the field name, the
 *                        child table and the parent fk
 */
public record ElementCollection(TypeName valueObjectType, Prop.ElementCollection element) {

    /** The declared field name on the owning type. */
    public String field() {
        return element.name();
    }

    /**
     * The value object's flattened column view, which drives the param bindings, so fk-bearing
     * children resolve through the same {@code boundValue} walk as parent-entity paths.
     */
    public ElementCollectionValueObjectMapping<?> descriptor() {
        return element.target();
    }

    public String childTable() {
        return element.childTable();
    }

    public String parentFk() {
        return element.parentFkColumn();
    }
}
