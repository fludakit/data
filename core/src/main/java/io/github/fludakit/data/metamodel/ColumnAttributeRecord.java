package io.github.fludakit.data.metamodel;

/** Plain column attribute for scalars of unknown sortability. */
public record ColumnAttributeRecord<T>(String name) implements ColumnAttribute<T> { }
