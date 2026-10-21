package io.github.fludakit.data.metamodel;

/** Record implementation of {@link EntityAttribute}. */
public record EntityAttributeRecord<T, U>(String name, PropertyPath path)
        implements EntityAttribute<T, U> {

    public EntityAttributeRecord(String name) {
        this(name, PropertyPath.of(name));
    }
}
