package io.github.fludakit.data.metamodel;

import jakarta.data.metamodel.TextAttribute;

/** A column attribute of a textual scalar, supporting case-insensitive ordering. */
public interface TextColumnAttribute<T> extends ColumnAttribute<T>, TextAttribute<T> { }
