package io.github.fludakit.data.metamodel;

/**
 * The plainest metamodel attribute: a name and a path, and no behaviour beyond them.
 *
 * <p>The one member of this family the generator never emits — every generated attribute is an id,
 * a scalar, a text or one of the three navigable kinds — so no code in this repository constructs
 * it. It is here for a hand-written metamodel, where an attribute has to be produced without a
 * generated class to hold it.</p>
 *
 * @param <T> the root entity type
 */
public record PathAttributeRecord<T>(String name, PropertyPath path) implements PathAttribute<T> { }
