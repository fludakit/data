package io.github.fludakit.data.processor.model;

import io.github.fludakit.data.metadata.ColumnPlacement;

/**
 * The outcome of resolving one entity-rooted attribute path against a {@code TypeMapping}.
 *
 * <p>Four outcomes, modeled as four types rather than one record with three boolean flags:</p>
 * <ul>
 *   <li>{@link Resolved} — the path names a column, reached with zero or more relation edges. Its
 *       {@code existential} flag is true when the head is a to-many relation, which the caller renders
 *       as an EXISTS semi-join instead of a JOIN.</li>
 *   <li>{@link MultiHop} — the path would need two or more joins; not supported, degraded.</li>
 *   <li>{@link UnresolvedRelation} — the relation head names a target that cannot be resolved; degraded.</li>
 *   <li>{@link Unknown} — the path names no known property.</li>
 * </ul>
 *
 * <p>The {@link #placement()} / {@link #attributePath()} / {@link #existential()} accessors answer
 * {@code null} / {@code false} for every non-{@link Resolved} outcome, so a caller that only needs the
 * column reads them uniformly; a caller that must distinguish the reasons switches on the subtype.</p>
 */
public sealed interface PathResolution {

    /** The column a path resolved to. {@code existential} is true when the head is a to-many. */
    record Resolved(ColumnPlacement placement, String attributePath, boolean existential)
            implements PathResolution {

        /** This resolution at an explicit entity-relative attribute path. */
        public Resolved at(String path) {
            return new Resolved(placement, path, existential);
        }
    }

    /** The path would need two or more joins — not supported. */
    record MultiHop() implements PathResolution {
    }

    /** The path's relation head names a target that could not be resolved. */
    record UnresolvedRelation() implements PathResolution {
    }

    /** The path names no known property. */
    record Unknown() implements PathResolution {
    }

    /** The resolved column placement, or {@code null} for a non-{@link Resolved} outcome. */
    default ColumnPlacement placement() {
        return this instanceof Resolved r ? r.placement() : null;
    }

    /** The authoritative attribute path, or {@code null} for a non-{@link Resolved} outcome. */
    default String attributePath() {
        return this instanceof Resolved r ? r.attributePath() : null;
    }

    /** Whether the head is a to-many relation; always false for a non-{@link Resolved} outcome. */
    default boolean existential() {
        return this instanceof Resolved r && r.existential();
    }
}
