package io.github.fludakit.data.metamodel;

import jakarta.data.metamodel.Attribute;

/**
 * An {@code List<@Entity>} one-to-many across the target's own table. Contributes no column to this
 * table; relation resolution needs the target class and the child property that points back.
 */
public interface OneToManyAttribute<T, U> extends Attribute<T> {

    Class<U> target();

    String inverseName();
}
