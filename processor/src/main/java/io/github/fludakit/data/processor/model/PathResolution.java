package io.github.fludakit.data.processor.model;

import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.processor.RepositoryProcessor;
import io.github.fludakit.data.processor.analysis.PathResolver;
import io.github.fludakit.data.processor.support.ResolutionVocabulary;

/**
 * The outcome of resolving a method-name property path against a {@code TypeMapping}. The carried
 * {@link ColumnPlacement} is the whole answer: the relation edges ({@code edges()}) say how to
 * reach the column, the leaf ({@code ref()}) says where it lives, and being one value they cannot
 * be handed over mismatched. A resolved path is either a plain local column (no edges) or a
 * relation-traversing column carrying its head edge(s); {@code multiHop} flags a path that would
 * need two or more joins (rejected with a NOTE), and {@code unresolvedRelation} flags a path whose
 * relation head names a target that could not be resolved (degraded with a NOTE). Both degrade
 * the caller back to the legacy unqualified rendering; neither aborts the compile.
 * {@code existential} marks a ref whose single head is a to-many
 * {@code EntityCollection}/{@code ElementCollection}: the caller renders it as an
 * EXISTS semi-join conjunct and splices NO join.
 *
 * <p>Lives outside {@link RepositoryProcessor} because both the processor and {@link PathResolver}
 * name it, and a nested type would force one to qualify the other's.</p>
 */
public record PathResolution(ColumnPlacement placement, boolean multiHop, boolean unresolvedRelation,
                      boolean existential, String attributePath) {
    public static PathResolution resolved(ColumnPlacement placement) { return new PathResolution(placement, false, false, false, null); }
    public static PathResolution existentialRef(ColumnPlacement placement) { return new PathResolution(placement, false, false, true, null); }
    /**
     * Rejected outright: no placement and no NOTE, so the caller SKIPS the method rather than
     * degrading it. Contrast {@link #relationUnresolved()}, which degrades and keeps going.
     */
    public static PathResolution unresolved() { return new PathResolution(null, false, false, false, null); }

    /** This resolution at an EXPLICIT entity-relative attribute path. */
    public PathResolution at(String path) {
        return new PathResolution(placement, multiHop, unresolvedRelation, existential, path);
    }

    /** The resolved leaf column, or null when the path did not resolve. */
    public ColumnRef ref() { return placement == null ? null : placement.leaf(); }

    /**
     * A path that would cross a SECOND relation — the {@link #multiHop()} state. Named for the
     * path, not for the rejection: one concept, one name, shared with
     * {@code ResolutionVocabulary.multiHop}.
     */
    public static PathResolution multiHopPath() { return new PathResolution(null, true, false, false, null); }

    /**
     * A relation head whose TARGET could not be built (no table, no inverse FK): distinct from
     * {@link #unresolved()}, which means "rejected outright" — this one still degrades to the
     * unqualified column behind a NOTE.
     */
    public static PathResolution relationUnresolved() { return new PathResolution(null, false, true, false, null); }
}
