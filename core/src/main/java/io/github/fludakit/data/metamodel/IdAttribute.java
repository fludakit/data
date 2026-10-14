package io.github.fludakit.data.metamodel;

/**
 * The entity identifier: a sortable scalar attribute usable as {@code _Entity.id.asc()}.
 *
 * <p>Declared as a subtype of {@link SortableScalarAttribute} rather than restating that interface's
 * own supertype pair ({@code ScalarAttribute} + {@code SortableAttribute}): the combination is
 * already named, and restating it let the two drift apart. The type itself stays, because "which
 * member is the id" is a distinct question in the generated metamodel — the field is emitted as
 * {@code IdAttribute<Book>}, not as {@code SortableScalarAttribute<Book>}.</p>
 *
 * @param <T> the root entity type
 */
public interface IdAttribute<T> extends SortableScalarAttribute<T> { }
