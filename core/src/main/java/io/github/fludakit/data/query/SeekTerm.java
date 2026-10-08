package io.github.fludakit.data.query;

import io.github.fludakit.data.metadata.ColumnRef;

/** One cursor key: the resolved column the seek predicate compares against, with its direction. */
public record SeekTerm(ColumnRef ref, boolean ascending) { }
