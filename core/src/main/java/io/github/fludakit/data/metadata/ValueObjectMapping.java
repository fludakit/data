package io.github.fludakit.data.metadata;

/**
 * A value object: a typed group of fields with no identity of its own.
 *
 * <p>Value objects never own relations and never own a lifecycle; they are always reached through
 * the entity that contains them. The two permitted shapes differ in WHERE their columns live, and
 * — this is the part worth stating — the difference is real enough to be two types:</p>
 *
 * <ul>
 *   <li>an {@link EmbeddedValueObjectMapping} flattens into its owner's row and has no table of its
 *       own, so it carries neither a table name nor a parent FK column;</li>
 *   <li>an {@link ElementCollectionValueObjectMapping} lives in a secondary child table of its own,
 *       linked back to the owner, so it carries BOTH — the table and the column referring to the
 *       owner. An element-collection value object genuinely has a table: that is why
 *       {@code tableName() == null} was never a safe test for "is this a value object".</li>
 * </ul>
 *
 * <p>Because both are table-less in the identity sense ({@link #id()} is {@code null}) and neither
 * is ever a traversal target in its own right, the shared parent is what callers switch on.</p>
 *
 * <p>Placement is NOT a property of the type, though — the child table is derived from the OWNER's
 * table and the collection field's name, so one value object placed by two owners is one field model
 * with two placements. The processor therefore builds the field model once (the single scan that
 * also produces the {@code <VO>Metadata} descriptor) and states the placement per use; see
 * {@code MappedTypeBuilder.buildCollection}.</p>
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
