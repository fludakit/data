package io.github.fludakit.data.metamodel;

import jakarta.data.metamodel.SortableAttribute;

/** The entity identifier: a sortable scalar attribute usable as {@code _Entity.id.asc()}. */
public interface IdAttribute<T> extends ScalarAttribute<T>, SortableAttribute<T> { }
