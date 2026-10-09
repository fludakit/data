package io.github.fludakit.data.metamodel;

import jakarta.data.metamodel.SortableAttribute;

/** A scalar attribute that supports ordering. */
public interface SortableScalarAttribute<T> extends ScalarAttribute<T>, SortableAttribute<T> { }
