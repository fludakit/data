package io.github.fludakit.data.processor.model;

import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.query.Predicate;

/**
 * One WHERE conjunct with its compile-time resolution: the logical {@link Predicate} plus the
 * {@link ColumnPlacement} its property path resolved to and the authoritative attribute path to
 * publish it under.
 *
 * <p>Bundled so a {@link QueryPlan} holds one list of self-contained conjuncts instead of three
 * index-aligned parallel lists.
 */
public record ResolvedPredicate(Predicate predicate, ColumnPlacement placement, String attributePath) {
}
