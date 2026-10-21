package io.github.fludakit.data.processor.context;

import io.github.fludakit.data.metadata.TypeDefinition;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Cache for {@link TypeDefinition} objects during annotation processing.
 * 
 * <p>This cache stores TypeDefinition objects keyed by their fully-qualified type name,
 * allowing the processor to avoid re-parsing entity models when multiple query methods
 * reference the same entity or when navigating through associations.</p>
 * 
 * <p>The cache is scoped to a single repository processing context and is not thread-safe.
 * Each repository being processed should have its own cache instance.</p>
 * 
 * <p>Example usage:</p>
 * <pre>
 * TypeDefinitionCache cache = new TypeDefinitionCache();
 * 
 * // Store a TypeDefinition
 * TypeDefinition.Entity orderEntity = ...;
 * cache.put("com.example.Order", orderEntity);
 * 
 * // Retrieve later
 * Optional&lt;TypeDefinition&gt; found = cache.get("com.example.Order");
 * </pre>
 */
public class TypeDefinitionCache {
    private final Map<String, TypeDefinition> cache = new HashMap<>();
    
    /**
     * Stores a TypeDefinition in the cache.
     * 
     * @param typeName the fully-qualified type name
     * @param definition the TypeDefinition to cache
     * @throws NullPointerException if typeName or definition is null
     */
    public void put(String typeName, TypeDefinition definition) {
        Objects.requireNonNull(typeName, "typeName must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        cache.put(typeName, definition);
    }
    
    /**
     * Retrieves a TypeDefinition from the cache.
     * 
     * @param typeName the fully-qualified type name
     * @return an Optional containing the TypeDefinition if found, or empty if not cached
     * @throws NullPointerException if typeName is null
     */
    public Optional<TypeDefinition> get(String typeName) {
        Objects.requireNonNull(typeName, "typeName must not be null");
        return Optional.ofNullable(cache.get(typeName));
    }
    
    /**
     * Checks if a TypeDefinition is cached for the given type name.
     * 
     * @param typeName the fully-qualified type name
     * @return true if cached, false otherwise
     * @throws NullPointerException if typeName is null
     */
    public boolean contains(String typeName) {
        Objects.requireNonNull(typeName, "typeName must not be null");
        return cache.containsKey(typeName);
    }
    
    /**
     * Returns the number of TypeDefinitions currently cached.
     * 
     * @return the cache size
     */
    public int size() {
        return cache.size();
    }
    
    /**
     * Clears all cached TypeDefinitions.
     */
    public void clear() {
        cache.clear();
    }
    
    /**
     * Returns an unmodifiable view of all cached type names.
     * 
     * @return set of cached type names
     */
    public java.util.Set<String> cachedTypeNames() {
        return java.util.Collections.unmodifiableSet(cache.keySet());
    }
}
