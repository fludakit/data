package io.github.fludakit.data.metamodel;

import jakarta.data.metamodel.SortableAttribute;

/** A column attribute of an orderable scalar (numeric, temporal, enum, boolean, ...). */
public interface SortableColumnAttribute<T> extends ColumnAttribute<T>, SortableAttribute<T> { }
