package io.github.fludakit.data.metamodel;

import jakarta.data.metamodel.TextAttribute;

/** A textual scalar attribute that supports case-insensitive ordering. */
public interface TextScalarAttribute<T> extends ScalarAttribute<T>, TextAttribute<T> { }
