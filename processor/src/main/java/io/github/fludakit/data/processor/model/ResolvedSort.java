package io.github.fludakit.data.processor.model;

import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.query.SortSpec;

/**
 * One ORDER BY term with its compile-time resolution: the logical {@link SortSpec} plus the
 * {@link ColumnPlacement} its property path resolved to, the authoritative attribute path, and
 * whether the head is a to-many relation (rendered as an aggregate subquery).
 */
public record ResolvedSort(SortSpec sort, ColumnPlacement placement, String attributePath,
                           boolean existential) {
}
