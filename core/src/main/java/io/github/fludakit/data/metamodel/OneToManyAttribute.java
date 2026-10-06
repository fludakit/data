package io.github.fludakit.data.metamodel;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.EntityDescriptor;
import jakarta.data.metamodel.Attribute;

import java.util.function.Supplier;

/**
 * An {@code List<@Entity>} one-to-many across the target's own table. Contributes no column to this
 * table; relation resolution needs the target class and the child property that points back.
 */
public interface OneToManyAttribute<T, U> extends Attribute<T> {

    Class<U> target();

    String inverseName();

    /** FK column on the target's own table pointing back here: snake(inverseName) + "_id". */
    default String childFkColumn() { return Names.fkColumn(inverseName()); }

    Supplier<? extends EntityDescriptor<U>> targetDescriptor();
}
