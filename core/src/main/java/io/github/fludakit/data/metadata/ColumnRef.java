package io.github.fludakit.data.metadata;

/** A flat physical column plus the dot-separated property path that selects it. */
public record ColumnRef(String column, String path) { }
