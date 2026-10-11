package io.github.fludakit.data.metadata;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@link AttributePathVisitor} that accumulates a walk: the relation edges crossed and the
 * terminal leaf column. Pass one to {@code TypeMapping.visit(path, resolution)} and read the result
 * back, or use it directly where a path must be resolved once and rendered.
 */
public final class ColumnResolution implements AttributePathVisitor {

    private ColumnRef leaf;
    private final List<ColumnRef> edges = new ArrayList<>();

    @Override
    public void edge(ColumnRef edge) {
        edges.add(edge);
    }

    @Override
    public void leaf(ColumnRef leaf) {
        this.leaf = leaf;
    }

    /** The terminal column of the walk. */
    public ColumnRef leaf() {
        return leaf;
    }

    /** The relation edges crossed, in walk order. */
    public List<ColumnRef> edges() {
        return List.copyOf(edges);
    }

    /** Whether the path is local to the root table (no relation edges crossed). */
    public boolean local() {
        return edges.isEmpty();
    }
}
