package io.github.fludakit.data.metadata;

/**
 * Kind of relation between entities or value objects.
 */
public enum RelationKind {
    /** To-one foreign key reference (ManyToOne or OneToOne owning side). */
    TO_ONE,

    /** To-many entity collection stored in the definition table (OneToMany or ManyToMany). */
    TO_MANY,

    /** Value object element collection stored in a child table. */
    ELEMENT_COLLECTION,

    /** Embedded value object flattened into the parent table. */
    EMBEDDED
}
