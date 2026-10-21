package io.github.fludakit.data.metamodel;

import java.util.Objects;

/**
 * A tree node representing a property navigation path from root entity to leaf property.
 *
 * <p>Each node holds exactly one property name (segment) and a reference to its parent node.
 * The full path is derived by walking from leaf to root, never stored as a string. This
 * structural representation enables compile-time path computation and eliminates string-based
 * path resolution at runtime.</p>
 *
 * <p>Paths are immutable and built via factory methods:</p>
 * <ul>
 *   <li>{@link #of(String)} creates a root-level path (single segment)</li>
 *   <li>{@link #child(String)} navigates to a nested property</li>
 * </ul>
 *
 * <p>Example:</p>
 * <pre>
 * PropertyPath customerId = PropertyPath.of("customer").child("id");
 * customerId.toDotPath();     // "customer.id"
 * customerId.toParamName();   // "customer_id"
 * customerId.segment();       // "id"
 * customerId.parent().segment(); // "customer"
 * </pre>
 *
 * <p>String forms ({@link #toDotPath()}, {@link #toParamName()}) are computed on demand at
 * SQL rendering boundaries, not stored. The tree structure IS the canonical representation.</p>
 */
public final class PropertyPath {

    private final PropertyPath parent;
    private final String segment;

    private PropertyPath(PropertyPath parent, String segment) {
        this.parent = parent;
        this.segment = Objects.requireNonNull(segment, "segment");
    }

    /**
     * Creates a root-level property path (single segment).
     *
     * @param segment the property name
     * @return a new root-level path
     */
    public static PropertyPath of(String segment) {
        return new PropertyPath(null, segment);
    }

    /**
     * Creates a child path by navigating from this path to a nested property.
     *
     * @param childSegment the nested property name
     * @return a new path representing this path + the child segment
     */
    public PropertyPath child(String childSegment) {
        return new PropertyPath(this, childSegment);
    }

    /**
     * Returns the leaf property name (the last segment of this path).
     *
     * @return the segment name
     */
    public String segment() {
        return segment;
    }

    /**
     * Returns the parent path, or {@code null} if this is a root-level path.
     *
     * @return the parent path, or null
     */
    public PropertyPath parent() {
        return parent;
    }

    /**
     * Computes the dot-separated property path (e.g., "customer.name").
     *
     * <p>This method walks the parent chain and joins segments with dots. It is called
     * only at SQL rendering boundaries, not stored. The tree structure is the canonical
     * representation.</p>
     *
     * @return the dot-separated path
     */
    public String toDotPath() {
        if (parent == null) {
            return segment;
        }
        return parent.toDotPath() + "." + segment;
    }

    /**
     * Computes the underscore-separated parameter name (e.g., "customer_name").
     *
     * <p>Used for SQL named parameters and bind variable names.</p>
     *
     * @return the underscore-separated name
     */
    public String toParamName() {
        if (parent == null) {
            return segment;
        }
        return parent.toParamName() + "_" + segment;
    }

    /**
     * Computes the column prefix for embedded value objects (e.g., "customer_").
     *
     * <p>Used when flattening embedded VO columns into the owning entity's table.</p>
     *
     * @return the underscore-separated prefix with trailing underscore
     */
    public String toColumnPrefix() {
        return toParamName() + "_";
    }

    /**
     * Returns the depth of this path (number of segments).
     *
     * <p>A root-level path has depth 1. A path "customer.name" has depth 2.</p>
     *
     * @return the path depth
     */
    public int depth() {
        int d = 1;
        PropertyPath p = parent;
        while (p != null) {
            d++;
            p = p.parent;
        }
        return d;
    }

    /**
     * Returns true if this path represents a navigation through an association or collection
     * (depth > 1).
     *
     * @return true if this is a nested path
     */
    public boolean isNested() {
        return parent != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PropertyPath that)) return false;
        return segment.equals(that.segment) && Objects.equals(parent, that.parent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parent, segment);
    }

    @Override
    public String toString() {
        return toDotPath();
    }
}
