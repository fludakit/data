package io.github.fludakit.data.metadata;

/**
 * A value object: a typed group of fields with no identity of its own.
 *
 * <p>Value objects never own relations and never own a lifecycle; they are always reached through
 * the entity that contains them. The two permitted shapes differ only in WHERE their columns live:
 * an {@link EmbeddedValueObjectMapping} flattens into its owner's table, an
 * {@link ElementCollectionValueObjectMapping} gets a child table of its own. Because both are
 * table-less in the identity sense ({@link #id()} is {@code null}) and neither is ever a traversal
 * target in its own right, the shared parent is what callers switch on.</p>
 */
public sealed interface ValueObjectMapping<T> extends TypeMapping<T>
        permits EmbeddedValueObjectMapping, ElementCollectionValueObjectMapping {

    @Override
    default boolean isValueObject() {
        return true;
    }

    /** A value object has no identity of its own. */
    @Override
    Prop.Id id();
}
