package io.github.fludakit.data.metamodel;

/** Record implementation of {@link EntityAttribute}. */
public record EntityAttributeRecord<T, U>(String name, String path)
        implements EntityAttribute<T, U> {

    public EntityAttributeRecord(String name) {
        this(name, name);
    }
}
