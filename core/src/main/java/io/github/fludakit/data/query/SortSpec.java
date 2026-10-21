package io.github.fludakit.data.query;

import io.github.fludakit.data.metadata.ResolvedPath;

/**
 * A sort specification for a query.
 *
 * <p>A sort spec carries either a property path as a string (for runtime resolution) or a
 * pre-resolved {@link ResolvedPath} (for compile-time resolution). When both are present, the
 * resolved path takes precedence.</p>
 *
 * @param property the dot-separated property path (e.g., "customer.name")
 * @param ascending true for ascending order, false for descending
 * @param ignoreCase true to perform case-insensitive sorting
 * @param resolvedPath the pre-resolved path, or null if not yet resolved
 */
public record SortSpec(String property, boolean ascending, boolean ignoreCase, ResolvedPath resolvedPath) {
    
    /**
     * Creates a sort spec with a string property path (unresolved).
     */
    public SortSpec(String property, boolean ascending, boolean ignoreCase) {
        this(property, ascending, ignoreCase, null);
    }
    
    /**
     * Creates a sort spec with a string property path (unresolved).
     */
    public static SortSpec of(String property, boolean ascending, boolean ignoreCase) {
        return new SortSpec(property, ascending, ignoreCase, null);
    }
    
    /**
     * Creates a sort spec with a pre-resolved path.
     *
     * @param resolvedPath the resolved property path
     * @param ascending true for ascending order, false for descending
     * @param ignoreCase true to perform case-insensitive sorting
     * @return a new sort spec with the resolved path
     */
    public static SortSpec of(ResolvedPath resolvedPath, boolean ascending, boolean ignoreCase) {
        return new SortSpec(resolvedPath.toDotPath(), ascending, ignoreCase, resolvedPath);
    }
    
    /**
     * Returns a new sort spec with the given resolved path.
     *
     * @param resolvedPath the resolved path to attach
     * @return a new sort spec with the resolved path
     */
    public SortSpec withResolvedPath(ResolvedPath resolvedPath) {
        return new SortSpec(this.property, this.ascending, this.ignoreCase, resolvedPath);
    }
    
    /**
     * Returns whether this sort spec has a pre-resolved path.
     */
    public boolean isResolved() {
        return resolvedPath != null;
    }
}
