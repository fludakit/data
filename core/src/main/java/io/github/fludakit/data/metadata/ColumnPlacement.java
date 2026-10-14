package io.github.fludakit.data.metadata;

import java.util.List;
import java.util.Objects;

/**
 * The immutable result of resolving ONE entity-rooted attribute path: the terminal column and the
 * relation edges that must be crossed to reach it.
 *
 * <p>This is the answer to "where is this attribute's column, and how do I get to it" — the two
 * halves that {@code ColumnRef} used to carry inside one type, which is why {@code ColumnRef}
 * needed {@code targetTable}/{@code childTable}/{@code ownerColumn} fields a column placement has no
 * business knowing about. Here they are separate and the pairing is structural: a caller cannot
 * hand a renderer a leaf and an edge list that belong to different resolutions.</p>
 *
 * <p>Immutable by construction, unlike the {@link ColumnResolution} visitor it supersedes.</p>
 *
 * @param leaf  the terminal column
 * @param edges the relation edges to cross, outermost first; empty for a root-table column
 */
public record ColumnPlacement(ColumnRef leaf, List<JoinEdge> edges) {

    public ColumnPlacement {
        Objects.requireNonNull(leaf, "leaf");
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    /** A placement on the root table: no edges to cross. */
    public ColumnPlacement(ColumnRef leaf) {
        this(leaf, List.of());
    }

    /** Whether the column is reached without crossing any relation edge. */
    public boolean local() {
        return edges.isEmpty();
    }
}
