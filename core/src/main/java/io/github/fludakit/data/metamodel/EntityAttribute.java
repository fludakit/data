package io.github.fludakit.data.metamodel;

/**
 * A to-one foreign-key association (in-aggregate many-to-one / one-to-one and external
 * {@code @Association} references): the Jakarta Data 1.1 {@code EntityAttribute} analogue.
 * Spec name ported FluDa-side; see {@link NavigableAttribute} for the re-point path.
 *
 * @param <T> the owner type
 * @param <U> the associated entity type
 */
public non-sealed interface EntityAttribute<T, U> extends NavigableAttribute<T, U> { }
