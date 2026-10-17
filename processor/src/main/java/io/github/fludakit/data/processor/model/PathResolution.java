package io.github.fludakit.data.processor.model;

import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.processor.RepositoryProcessor;
import io.github.fludakit.data.processor.analysis.PathResolver;

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
 *
 * <p>Pure data: construction is through the record constructor. Factory methods for the common
 * shapes (resolved, unresolved, multi-hop, etc.) live in {@link PathResolver}, the only caller.</p>
 */
public record PathResolution(ColumnPlacement placement, boolean multiHop, boolean unresolvedRelation,
                      boolean existential, String attributePath) {

    /** This resolution at an EXPLICIT entity-relative attribute path. */
    public PathResolution at(String path) {
        return new PathResolution(placement, multiHop, unresolvedRelation, existential, path);
    }

    /** The resolved leaf column, or null when the path did not resolve. */
    public ColumnRef ref() { return placement == null ? null : placement.leaf(); }
}
