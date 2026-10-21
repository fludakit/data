package io.github.fludakit.data.processor.resolution;

import io.github.fludakit.data.metadata.ResolvedPath;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.context.TypeDefinitionCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Resolves property path strings to {@link ResolvedPath} objects by walking the {@link TypeDefinition} graph.
 * 
 * <p>This resolver serves as the unified entry point for all property path resolution in the processor:
 * <ul>
 *   <li>Query By Method Name (QBN): {@code findByCustomerName} → "customer.name"</li>
 *   <li>@By annotation: {@code @By("customer.name")}</li>
 *   <li>@OrderBy annotation: {@code @OrderBy("customer.name ASC")}</li>
 *   <li>Runtime Sort: {@code Sort.asc("customer.name")}</li>
 *   <li>PageRequest with Sort: {@code PageRequest.of(1, 10).withSort(Sort.asc("customer.name"))}</li>
 * </ul>
 * 
 * <p>The resolver splits the path by dots, then walks the TypeDefinition graph starting from the root
 * entity. For each segment, it looks up the corresponding property in the current TypeDefinition and
 * advances to the target type (for associations, embedded, or collection properties).</p>
 * 
 * <p>Example:</p>
 * <pre>
 * PathResolver resolver = new PathResolver(typeDefinitionCache);
 * ResolvedPath path = resolver.resolve("customer.name", orderEntity);
 * // path contains: [Association(customer), Scalar(name)]
 * // path.columnName() returns "name"
 * // path.isLocal() returns false
 * </pre>
 * 
 * <p>This class is stateless and thread-safe. It uses the provided {@link TypeDefinitionCache} to
 * look up related entity types as it navigates through associations.</p>
 */
public class PathResolver {
    private final TypeDefinitionCache cache;
    
    /**
     * Creates a new PathResolver with the given TypeDefinition cache.
     * 
     * @param cache the cache containing TypeDefinition objects
     * @throws NullPointerException if cache is null
     */
    public PathResolver(TypeDefinitionCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
    }
    
    /**
     * Resolves a property path string against a root entity TypeDefinition.
     * 
     * @param propertyPath the dot-separated property path (e.g., "customer.name")
     * @param rootEntity the root entity TypeDefinition to start resolution from
     * @return the resolved path containing the list of properties from root to leaf
     * @throws NullPointerException if propertyPath or rootEntity is null
     * @throws PathResolutionException if the path cannot be resolved (property not found, invalid navigation, etc.)
     */
    public ResolvedPath resolve(String propertyPath, TypeDefinition.Entity rootEntity) {
        Objects.requireNonNull(propertyPath, "propertyPath must not be null");
        Objects.requireNonNull(rootEntity, "rootEntity must not be null");
        
        if (propertyPath.isBlank()) {
            throw new PathResolutionException("Property path must not be blank", propertyPath);
        }
        
        String[] segments = propertyPath.split("\\.");
        List<TypeDefinition.Prop> resolvedProps = new ArrayList<>();
        
        TypeDefinition currentType = rootEntity;
        
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            
            if (currentType == null) {
                throw new PathResolutionException(
                    "Cannot navigate through segment '" + segment + "': current type is null",
                    propertyPath);
            }
            
            TypeDefinition.Prop prop = findProperty(currentType, segment);
            if (prop == null) {
                throw new PathResolutionException(
                    "Property '" + segment + "' not found in type " + currentType.typeName(),
                    propertyPath);
            }
            
            resolvedProps.add(prop);
            
            // Advance to the target type for the next segment
            if (i < segments.length - 1) {
                currentType = getTargetType(prop, propertyPath, segment);
            }
        }
        
        return ResolvedPath.of(resolvedProps);
    }
    
    /**
     * Finds a property by name in the given TypeDefinition.
     */
    private TypeDefinition.Prop findProperty(TypeDefinition type, String propertyName) {
        if (type instanceof TypeDefinition.Entity entity) {
            // Check ID property
            if (entity.id() != null && entity.id().name().equals(propertyName)) {
                return entity.id();
            }
            
            // Check version property
            if (entity.version() != null && entity.version().name().equals(propertyName)) {
                return entity.version();
            }
            
            // Check all other properties
            for (TypeDefinition.Prop prop : entity.properties()) {
                if (prop.name().equals(propertyName)) {
                    return prop;
                }
            }
        } else if (type instanceof TypeDefinition.ValueObject vo) {
            for (TypeDefinition.Prop prop : vo.properties()) {
                if (prop.name().equals(propertyName)) {
                    return prop;
                }
            }
        } else if (type instanceof TypeDefinition.Aggregate agg) {
            // Check ID property
            if (agg.id() != null && agg.id().name().equals(propertyName)) {
                return agg.id();
            }
            
            // Check all properties
            for (TypeDefinition.Prop prop : agg.properties()) {
                if (prop.name().equals(propertyName)) {
                    return prop;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Gets the target TypeDefinition for navigation through a property.
     */
    private TypeDefinition getTargetType(TypeDefinition.Prop prop, String fullPath, String segment) {
        if (prop instanceof TypeDefinition.Prop.Association assoc) {
            String targetTypeName = assoc.definition().typeName();
            return cache.get(targetTypeName)
                .orElseThrow(() -> new PathResolutionException(
                    "Target type '" + targetTypeName + "' for association '" + segment + "' not found in cache",
                    fullPath));
        } else if (prop instanceof TypeDefinition.Prop.Embedded emb) {
            String targetTypeName = emb.definition().typeName();
            return cache.get(targetTypeName)
                .orElseThrow(() -> new PathResolutionException(
                    "Target type '" + targetTypeName + "' for embedded '" + segment + "' not found in cache",
                    fullPath));
        } else if (prop instanceof TypeDefinition.Prop.EntityCollection ec) {
            String targetTypeName = ec.definition().typeName();
            return cache.get(targetTypeName)
                .orElseThrow(() -> new PathResolutionException(
                    "Target type '" + targetTypeName + "' for collection '" + segment + "' not found in cache",
                    fullPath));
        } else if (prop instanceof TypeDefinition.Prop.ElementCollection ec) {
            String targetTypeName = ec.definition().typeName();
            return cache.get(targetTypeName)
                .orElseThrow(() -> new PathResolutionException(
                    "Target type '" + targetTypeName + "' for element collection '" + segment + "' not found in cache",
                    fullPath));
        } else {
            throw new PathResolutionException(
                "Cannot navigate through scalar/id property '" + segment + "'",
                fullPath);
        }
    }
    
    /**
     * Exception thrown when a property path cannot be resolved.
     */
    public static class PathResolutionException extends RuntimeException {
        private final String propertyPath;
        
        public PathResolutionException(String message, String propertyPath) {
            super(message);
            this.propertyPath = propertyPath;
        }
        
        public String propertyPath() {
            return propertyPath;
        }
    }
}
