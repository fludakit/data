package io.github.fludakit.data.metamodel;

/**
 * An ordered, navigable collection persisted in a secondary table — covers BOTH the
 * {@code List<@Entity>} one-to-many (joined by the child's inverse fk) and the
 * {@code List<@ValueObject>} element collection (child table owned by the parent): the Jakarta Data
 * 1.1 {@code ListAttribute} analogue.
 *
 * @param <T> the owner type
 * @param <E> the element type
 */
public non-sealed interface ListAttribute<T, E> extends NavigableAttribute<T, E> { }
