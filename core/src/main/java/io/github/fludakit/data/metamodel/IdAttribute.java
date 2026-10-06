package io.github.fludakit.data.metamodel;

import jakarta.data.metamodel.SortableAttribute;

/** The entity identifier: a sortable column attribute usable as {@code _Entity.id.asc()}. */
public interface IdAttribute<T> extends ColumnAttribute<T>, SortableAttribute<T> { }
