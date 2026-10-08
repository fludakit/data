package io.github.fludakit.data.query;

import io.github.fludakit.data.metadata.ColumnRef;

/**
 * One ORDER BY term with its resolved physical placement; {@code existential} marks a to-many head —
 * rendered as an aggregate subquery sort term, never a join.
 */
public record ResolvedSort(SortSpec sort, ColumnRef ref, boolean existential) { }
