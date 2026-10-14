package io.github.fludakit.data.metadata;

/**
 * An attribute path the mapping DOES publish but that has no single table-local column: it crosses
 * at least one edge, so its physical column lives in another table.
 *
 * <p>Split from {@link UnknownAttributePathException} because the two are answered in opposite
 * directions. An unknown path is a user error and there is nothing to fall back to — it becomes a
 * compile-time ERROR naming the property. A non-local path is a KNOWN path the flat column view
 * simply cannot express: a sort over a to-many renders as an aggregate subquery term, so the caller
 * that can express it does, and only a caller that cannot treats it as an error. While both were
 * plain {@link IllegalArgumentException} the only way to tell them apart was to catch the supertype
 * and re-resolve, which meant an unrelated illegal-argument failure raised inside the walk took the
 * same branch as one of them.</p>
 *
 * <p>It also means {@code QueryCompiler#resolveColumn}'s degradation to a bare snake_cased root-table
 * column no longer applies to this case. Degrading an unknown path is a guess that renders something;
 * degrading a known-but-crossing path is a guess that renders the WRONG column, since the path does
 * exist — just not here. So this one propagates.</p>
 *
 * <p>The placement rides along because the caller that recovers needs it and re-resolving to get it
 * back would ask the mapping the same question twice.</p>
 */
public final class NonLocalAttributePathException extends IllegalArgumentException {

    private final String path;
    private final ColumnPlacement placement;

    public NonLocalAttributePathException(String path, ColumnPlacement placement) {
        super("'" + path + "' traverses a relationship and has no table-local column");
        this.path = path;
        this.placement = placement;
    }

    /** The path that resolved to a column in another table. */
    public String path() {
        return path;
    }

    /** Where the path actually lands, for a caller that can render it relation-aware. */
    public ColumnPlacement placement() {
        return placement;
    }
}
