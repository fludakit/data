package io.github.fludakit.data.metamodel;

/**
 * A value-object property flattened into the owner's table: the Jakarta Data 1.1
 * {@code EmbeddableAttribute} analogue (nested members navigate through generated leaf fields; the
 * spec's {@code element()} recursion is realized here by the {@code _<Owner><Field>} nav classes).
 *
 * @param <T> the owner type
 * @param <V> the value object type
 */
public non-sealed interface EmbeddableAttribute<T, V> extends NavigableAttribute<T, V> { }
