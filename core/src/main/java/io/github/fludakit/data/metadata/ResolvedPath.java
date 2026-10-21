package io.github.fludakit.data.metadata;

import io.github.fludakit.data.metadata.TypeDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A resolved property path from root entity to a leaf property.
 * 
 * <p>Unlike {@link PropertyPath} which is a compile-time tree structure for the static metamodel,
 * {@code ResolvedPath} is a runtime representation that holds the actual {@link TypeDefinition.Prop}
 * nodes encountered during path resolution. This allows the query processor to access column names,
 * JOIN information, and other metadata without re-parsing the path string.</p>
 * 
 * <p>A resolved path is built by walking the {@link TypeDefinition} graph starting from the root
 * entity and following each segment of the property path. For example, resolving "customer.name"
 * on an Order entity would produce a path containing:</p>
 * <ol>
 *   <li>The Association prop for "customer"</li>
 *   <li>The Scalar prop for "name" in the Customer entity</li>
 * </ol>
 * 
 * <p>This class is immutable and thread-safe.</p>
 * 
 * @param props the list of properties from root to leaf (never null or empty)
 */
public final class ResolvedPath {
    private final List<TypeDefinition.Prop> props;
    
    private ResolvedPath(List<TypeDefinition.Prop> props) {
        this.props = Collections.unmodifiableList(new ArrayList<>(props));
    }
    
    /**
     * Creates a resolved path from a list of properties.
     * 
     * @param props the properties from root to leaf (must not be null or empty)
     * @return a new ResolvedPath
     * @throws IllegalArgumentException if props is null or empty
     */
    public static ResolvedPath of(List<TypeDefinition.Prop> props) {
        Objects.requireNonNull(props, "props must not be null");
        if (props.isEmpty()) {
            throw new IllegalArgumentException("props must not be empty");
        }
        return new ResolvedPath(props);
    }
    
    /**
     * Creates a resolved path from a single property (leaf node).
     * 
     * @param prop the property
     * @return a new ResolvedPath
     */
    public static ResolvedPath of(TypeDefinition.Prop prop) {
        return of(List.of(prop));
    }
    
    /**
     * Returns the list of properties from root to leaf.
     * 
     * @return unmodifiable list of properties (never null or empty)
     */
    public List<TypeDefinition.Prop> props() {
        return props;
    }
    
    /**
     * Returns the leaf property (the last element in the path).
     * 
     * @return the leaf property
     */
    public TypeDefinition.Prop leaf() {
        return props.get(props.size() - 1);
    }
    
    /**
     * Returns the root property (the first element in the path).
     * 
     * @return the root property
     */
    public TypeDefinition.Prop root() {
        return props.get(0);
    }
    
    /**
     * Returns the column name for the leaf property.
     * 
     * <p>For scalar and ID properties, this is the direct column name.
     * For other property types, this throws an exception.</p>
     * 
     * @return the column name
     * @throws IllegalStateException if the leaf is not a scalar or ID property
     */
    public String columnName() {
        TypeDefinition.Prop leaf = leaf();
        if (leaf instanceof TypeDefinition.Prop.Scalar s) {
            return s.columnName();
        } else if (leaf instanceof TypeDefinition.Prop.Id id) {
            return id.columnName();
        } else {
            throw new IllegalStateException(
                "Cannot get column name for non-sleaf leaf property: " + leaf.getClass().getSimpleName());
        }
    }
    
    /**
     * Returns the number of segments in the path.
     * 
     * @return the path length
     */
    public int length() {
        return props.size();
    }
    
    /**
     * Returns true if this path represents a local property (no navigation through associations).
     * 
     * <p>A path is local if it contains only a single property that is a scalar, ID, or embedded
     * property of the root entity.</p>
     * 
     * @return true if local, false if the path navigates through associations or collections
     */
    public boolean isLocal() {
        if (props.size() == 1) {
            TypeDefinition.Prop root = props.get(0);
            return root instanceof TypeDefinition.Prop.Scalar 
                || root instanceof TypeDefinition.Prop.Id
                || root instanceof TypeDefinition.Prop.Embedded;
        }
        return false;
    }
    
    /**
     * Returns true if the path navigates through a to-many relationship (EntityCollection or ElementCollection).
     * 
     * @return true if any property in the path is a collection
     */
    public boolean isToMany() {
        return props.stream().anyMatch(p -> 
            p instanceof TypeDefinition.Prop.EntityCollection 
            || p instanceof TypeDefinition.Prop.ElementCollection);
    }
    
    /**
     * Returns the dot-separated path string (e.g., "customer.name").
     * 
     * @return the dot path
     */
    public String toDotPath() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < props.size(); i++) {
            if (i > 0) sb.append('.');
            sb.append(props.get(i).name());
        }
        return sb.toString();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ResolvedPath that)) return false;
        return props.equals(that.props);
    }
    
    @Override
    public int hashCode() {
        return props.hashCode();
    }
    
    @Override
    public String toString() {
        return "ResolvedPath[" + toDotPath() + "]";
    }
}
