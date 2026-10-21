package io.github.fludakit.data.processor.context;

import io.github.fludakit.data.metadata.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TypeDefinitionCache}.
 */
class TypeDefinitionCacheTest {
    
    @Test
    void putAndGet() {
        TypeDefinitionCache cache = new TypeDefinitionCache();
        
        TypeDefinition.Prop.Id idProp = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id",
            TypeDefinition.Prop.Id.GeneratedType.AUTO);
        TypeDefinition.Entity entity = new TypeDefinition.Entity(
            "com.example.Customer", "customer", idProp, null,
            List.of(), List.of(), List.of(), List.of(), List.of());
        
        cache.put("com.example.Customer", entity);
        
        assertTrue(cache.contains("com.example.Customer"));
        assertEquals(1, cache.size());
        
        var retrieved = cache.get("com.example.Customer");
        assertTrue(retrieved.isPresent());
        assertEquals(entity, retrieved.get());
    }
    
    @Test
    void getReturnsEmptyForMissingType() {
        TypeDefinitionCache cache = new TypeDefinitionCache();
        
        var result = cache.get("com.example.NonExistent");
        
        assertFalse(result.isPresent());
        assertFalse(cache.contains("com.example.NonExistent"));
    }
    
    @Test
    void putOverwritesExistingEntry() {
        TypeDefinitionCache cache = new TypeDefinitionCache();
        
        TypeDefinition.Prop.Id idProp1 = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id",
            TypeDefinition.Prop.Id.GeneratedType.AUTO);
        TypeDefinition.Entity entity1 = new TypeDefinition.Entity(
            "com.example.Customer", "customer", idProp1, null,
            List.of(), List.of(), List.of(), List.of(), List.of());
        
        TypeDefinition.Prop.Id idProp2 = new TypeDefinition.Prop.Id("id", "java.lang.Long", "customer_id",
            TypeDefinition.Prop.Id.GeneratedType.IDENTITY);
        TypeDefinition.Entity entity2 = new TypeDefinition.Entity(
            "com.example.Customer", "customer", idProp2, null,
            List.of(), List.of(), List.of(), List.of(), List.of());
        
        cache.put("com.example.Customer", entity1);
        assertEquals(entity1, cache.get("com.example.Customer").get());
        
        cache.put("com.example.Customer", entity2);
        assertEquals(entity2, cache.get("com.example.Customer").get());
        assertEquals(1, cache.size());
    }
    
    @Test
    void clearRemovesAllEntries() {
        TypeDefinitionCache cache = new TypeDefinitionCache();
        
        TypeDefinition.Prop.Id idProp = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id",
            TypeDefinition.Prop.Id.GeneratedType.AUTO);
        TypeDefinition.Entity entity = new TypeDefinition.Entity(
            "com.example.Customer", "customer", idProp, null,
            List.of(), List.of(), List.of(), List.of(), List.of());
        
        cache.put("com.example.Customer", entity);
        cache.put("com.example.Order", entity);
        
        assertEquals(2, cache.size());
        
        cache.clear();
        
        assertEquals(0, cache.size());
        assertFalse(cache.contains("com.example.Customer"));
        assertFalse(cache.contains("com.example.Order"));
    }
    
    @Test
    void cachedTypeNamesReturnsAllKeys() {
        TypeDefinitionCache cache = new TypeDefinitionCache();
        
        TypeDefinition.Prop.Id idProp = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id",
            TypeDefinition.Prop.Id.GeneratedType.AUTO);
        TypeDefinition.Entity entity = new TypeDefinition.Entity(
            "com.example.Customer", "customer", idProp, null,
            List.of(), List.of(), List.of(), List.of(), List.of());
        
        cache.put("com.example.Customer", entity);
        cache.put("com.example.Order", entity);
        
        var typeNames = cache.cachedTypeNames();
        
        assertEquals(2, typeNames.size());
        assertTrue(typeNames.contains("com.example.Customer"));
        assertTrue(typeNames.contains("com.example.Order"));
    }
    
    @Test
    void putRejectsNullTypeName() {
        TypeDefinitionCache cache = new TypeDefinitionCache();
        
        TypeDefinition.Prop.Id idProp = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id",
            TypeDefinition.Prop.Id.GeneratedType.AUTO);
        TypeDefinition.Entity entity = new TypeDefinition.Entity(
            "com.example.Customer", "customer", idProp, null,
            List.of(), List.of(), List.of(), List.of(), List.of());
        
        assertThrows(NullPointerException.class, () -> cache.put(null, entity));
    }
    
    @Test
    void putRejectsNullDefinition() {
        TypeDefinitionCache cache = new TypeDefinitionCache();
        
        assertThrows(NullPointerException.class, () -> cache.put("com.example.Customer", null));
    }
    
    @Test
    void getRejectsNullTypeName() {
        TypeDefinitionCache cache = new TypeDefinitionCache();
        
        assertThrows(NullPointerException.class, () -> cache.get(null));
    }
    
    @Test
    void containsRejectsNullTypeName() {
        TypeDefinitionCache cache = new TypeDefinitionCache();
        
        assertThrows(NullPointerException.class, () -> cache.contains(null));
    }
}
