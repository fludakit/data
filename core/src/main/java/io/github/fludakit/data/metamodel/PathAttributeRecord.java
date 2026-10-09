package io.github.fludakit.data.metamodel;

/** A path-only metamodel attribute used when no generated static-metamodel field is available. */
public record PathAttributeRecord<T>(String name, String path) implements PathAttribute<T> { }
