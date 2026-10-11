package io.github.fludakit.data.metadata;

/**
 * Consumes the steps of a dot-path walk through the aggregate tree. The walk reports each relation
 * hop as an {@link #edge} (a {@link ColumnRef.Reference} or {@link ColumnRef.Collection}) and the
 * terminal column as a {@link #leaf}, so the consumer assembles the join chain and the leaf
 * qualification without an intermediate result object.
 */
public interface AttributePathVisitor {

    /** A relation edge crossed along the path. */
    void edge(ColumnRef edge);

    /** The terminal column of the path. */
    void leaf(ColumnRef leaf);
}
