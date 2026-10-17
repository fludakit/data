package io.github.fludakit.data.processor.support;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.JoinEdge;

/**
 * Builds typed {@link JoinEdge}s from raw annotation names. Split from {@code PathResolver}: that
 * class answers "which column does this property reach", these methods answer "what join does this
 * raw table/column pair name" — a different question with different callers.
 */
public final class JoinEdges {

    private JoinEdges() {
    }

    /**
     * A {@code ToOne} edge, or {@code null} when any endpoint is still unresolved.
     *
     * <p>An edge names FOUR physical things and a half-built one is worse than none: it would emit
     * a {@code LEFT JOIN "null" ...}. The owner is not table-backed in one real case — a
     * {@code @ValueObject} analysed on its own has no table until an owner makes it an element
     * collection — so "unbuildable" is a legitimate outcome and every caller skips it.</p>
     *
     * <p>The caller still hands over raw names — they come from annotations, not from a mapping —
     * and this is where they are bound into the two typed endpoints: the owner side is the
     * {@code ForeignKey}, the child side the identifier it points at.</p>
     */
    public static JoinEdge toOneOrNull(String ownerTable, String ownerColumn,
                                String childTable, String childColumn) {
        return complete(ownerTable, ownerColumn, childTable, childColumn)
                ? new JoinEdge.ToOne(new ColumnRef.ForeignKey(ownerColumn, ownerTable),
                new ColumnRef.Simple(childColumn, childTable))
                : null;
    }

    /** As {@link #toOneOrNull} for a to-many edge: the {@code ForeignKey} is on the CHILD side. */
    public static JoinEdge toManyOrNull(String ownerTable, String ownerColumn,
                                 String childTable, String childColumn) {
        return complete(ownerTable, ownerColumn, childTable, childColumn)
                ? new JoinEdge.ToMany(new ColumnRef.Simple(ownerColumn, ownerTable),
                new ColumnRef.ForeignKey(childColumn, childTable))
                : null;
    }

    public static boolean complete(String ownerTable, String ownerColumn,
                            String childTable, String childColumn) {
        return ownerTable != null && ownerColumn != null && childTable != null && childColumn != null;
    }
}
