package io.github.fludakit.data.processor.resolution;

import io.github.fludakit.data.metadata.ResolvedPath;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.context.TypeDefinitionCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PathResolver}.
 */
class PathResolverTest {
    
    private TypeDefinitionCache cache;
    private PathResolver resolver;
    private TypeDefinition.Entity orderEntity;
    private TypeDefinition.Entity customerEntity;
    private TypeDefinition.ValueObject addressVO;
    
    @BeforeEach
    void setUp() {
        cache = new TypeDefinitionCache();
        resolver = new PathResolver(cache);
        
        // Create Customer entity with id, name, and address (embedded)
        TypeDefinition.Prop.Id customerId = new TypeDefinition.Prop.Id(
            "id", "java.lang.Long", "id", TypeDefinition.Prop.Id.GeneratedType.AUTO);
        TypeDefinition.Prop.Scalar customerName = new TypeDefinition.Prop.Scalar(
            "name", "java.lang.String", "name", true);
        
        addressVO = new TypeDefinition.ValueObject(
            "com.example.Address",
            List.of(new TypeDefinition.Prop.Scalar("city", "java.lang.String", "city", true)),
            List.of(), List.of(), List.of(), List.of());
        
        TypeDefinition.Prop.Embedded customerAddress = new TypeDefinition.Prop.Embedded(
            "address", "com.example.Address", addressVO, "address_");
        
        customerEntity = new TypeDefinition.Entity(
            "com.example.Customer", "customer", customerId, null,
            List.of(customerName), List.of(customerAddress), List.of(), List.of(), List.of());
        
        // Create Order entity with id, orderDate, and customer (association)
        TypeDefinition.Prop.Id orderId = new TypeDefinition.Prop.Id(
            "id", "java.lang.Long", "id", TypeDefinition.Prop.Id.GeneratedType.AUTO);
        TypeDefinition.Prop.Scalar orderDate = new TypeDefinition.Prop.Scalar(
            "orderDate", "java.time.LocalDate", "order_date", true);
        TypeDefinition.Prop.Association orderCustomer = new TypeDefinition.Prop.Association(
            "customer", "com.example.Customer", customerEntity, "customer_id", 
            false, false, TypeDefinition.Prop.Association.Cascade.ALL);
        
        orderEntity = new TypeDefinition.Entity(
            "com.example.Order", "order", orderId, null,
            List.of(orderDate), List.of(), List.of(orderCustomer), List.of(), List.of());
        
        // Cache all types
        cache.put("com.example.Order", orderEntity);
        cache.put("com.example.Customer", customerEntity);
        cache.put("com.example.Address", addressVO);
    }
    
    @Test
    void resolveSingleScalarProperty() {
        ResolvedPath path = resolver.resolve("orderDate", orderEntity);
        
        assertEquals(1, path.length());
        assertEquals("orderDate", path.toDotPath());
        assertEquals("order_date", path.columnName());
        assertTrue(path.isLocal());
        assertFalse(path.isToMany());
    }
    
    @Test
    void resolveSingleIdProperty() {
        ResolvedPath path = resolver.resolve("id", orderEntity);
        
        assertEquals(1, path.length());
        assertEquals("id", path.toDotPath());
        assertEquals("id", path.columnName());
        assertTrue(path.isLocal());
    }
    
    @Test
    void resolveAssociationNavigation() {
        ResolvedPath path = resolver.resolve("customer.name", orderEntity);
        
        assertEquals(2, path.length());
        assertEquals("customer.name", path.toDotPath());
        assertEquals("name", path.columnName());
        assertFalse(path.isLocal());
        assertFalse(path.isToMany());
        
        assertTrue(path.root() instanceof TypeDefinition.Prop.Association);
        assertTrue(path.leaf() instanceof TypeDefinition.Prop.Scalar);
    }
    
    @Test
    void resolveEmbeddedNavigation() {
        ResolvedPath path = resolver.resolve("address.city", customerEntity);
        
        assertEquals(2, path.length());
        assertEquals("address.city", path.toDotPath());
        assertEquals("city", path.columnName());
        assertFalse(path.isLocal());
        
        assertTrue(path.root() instanceof TypeDefinition.Prop.Embedded);
        assertTrue(path.leaf() instanceof TypeDefinition.Prop.Scalar);
    }
    
    @Test
    void resolveMultiHopNavigation() {
        ResolvedPath path = resolver.resolve("customer.address.city", orderEntity);
        
        assertEquals(3, path.length());
        assertEquals("customer.address.city", path.toDotPath());
        assertEquals("city", path.columnName());
        assertFalse(path.isLocal());
    }
    
    @Test
    void resolveThrowsForBlankPath() {
        assertThrows(PathResolver.PathResolutionException.class, 
            () -> resolver.resolve("", orderEntity));
        assertThrows(PathResolver.PathResolutionException.class, 
            () -> resolver.resolve("   ", orderEntity));
    }
    
    @Test
    void resolveThrowsForUnknownProperty() {
        PathResolver.PathResolutionException ex = assertThrows(
            PathResolver.PathResolutionException.class,
            () -> resolver.resolve("unknownProperty", orderEntity));
        
        assertTrue(ex.getMessage().contains("unknownProperty"));
        assertTrue(ex.getMessage().contains("not found"));
        assertEquals("unknownProperty", ex.propertyPath());
    }
    
    @Test
    void resolveThrowsForUnknownNestedProperty() {
        PathResolver.PathResolutionException ex = assertThrows(
            PathResolver.PathResolutionException.class,
            () -> resolver.resolve("customer.unknownField", orderEntity));
        
        assertTrue(ex.getMessage().contains("unknownField"));
        assertEquals("customer.unknownField", ex.propertyPath());
    }
    
    @Test
    void resolveThrowsForNavigationThroughScalar() {
        PathResolver.PathResolutionException ex = assertThrows(
            PathResolver.PathResolutionException.class,
            () -> resolver.resolve("orderDate.something", orderEntity));
        
        assertTrue(ex.getMessage().contains("Cannot navigate through scalar"));
        assertEquals("orderDate.something", ex.propertyPath());
    }
    
    @Test
    void resolveThrowsForMissingTypeInCache() {
        // Create an order entity with association to a type not in cache
        TypeDefinition.Prop.Id orderId = new TypeDefinition.Prop.Id(
            "id", "java.lang.Long", "id", TypeDefinition.Prop.Id.GeneratedType.AUTO);
        TypeDefinition.Entity missingTargetEntity = new TypeDefinition.Entity(
            "com.example.Missing", "missing", orderId, null,
            List.of(), List.of(), List.of(), List.of(), List.of());
        TypeDefinition.Prop.Association assocToMissing = new TypeDefinition.Prop.Association(
            "missing", "com.example.Missing", missingTargetEntity, "missing_id",
            false, false, TypeDefinition.Prop.Association.Cascade.ALL);
        
        TypeDefinition.Entity testEntity = new TypeDefinition.Entity(
            "com.example.Test", "test", orderId, null,
            List.of(), List.of(), List.of(assocToMissing), List.of(), List.of());
        
        // Don't cache the missing target entity
        PathResolver.PathResolutionException ex = assertThrows(
            PathResolver.PathResolutionException.class,
            () -> resolver.resolve("missing.field", testEntity));
        
        assertTrue(ex.getMessage().contains("not found in cache"));
    }
    
    @Test
    void resolveWithNullPropertyPathThrowsNPE() {
        assertThrows(NullPointerException.class, 
            () -> resolver.resolve(null, orderEntity));
    }
    
    @Test
    void resolveWithNullRootEntityThrowsNPE() {
        assertThrows(NullPointerException.class, 
            () -> resolver.resolve("id", null));
    }
    
    @Test
    void constructorWithNullCacheThrowsNPE() {
        assertThrows(NullPointerException.class, 
            () -> new PathResolver(null));
    }
}
