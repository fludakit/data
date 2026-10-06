package io.github.fludakit.data.metamodel;

/** Record implementation of {@link OneToManyAttribute}. */
public record OneToManyAttributeRecord<T, U>(String name, Class<U> target, String inverseName)
        implements OneToManyAttribute<T, U> { }
