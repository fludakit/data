package io.github.fludakit.data.metamodel;

import io.github.fludakit.data.metadata.EntityDescriptor;

import java.util.function.Supplier;

/**
 * A foreign-key column attribute (in-aggregate many-to-one / one-to-one, and external
 * {@code @Association} references): the fk column plus the target entity class.
 */
public interface ForeignKeyAttribute<T, U> extends ColumnAttribute<T> {

    Class<U> target();

    Supplier<? extends EntityDescriptor<U>> targetDescriptor();
}
