package io.github.fludakit.data.query;

import io.github.fludakit.data.metadata.ColumnRef;

/**
 * One WHERE conjunct with its resolved physical placement: {@code predicate} carries the property
 * path, operator and param token; {@code ref} the column/joins it renders against;
 * {@code existential} marks a to-many head — rendered as an {@code EXISTS} semi-join conjunct
 * instead of a fanning LEFT JOIN.
 */
public record ResolvedPredicate(Predicate predicate, ColumnRef ref, boolean existential) { }
