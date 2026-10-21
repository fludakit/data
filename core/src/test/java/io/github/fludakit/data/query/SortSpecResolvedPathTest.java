package io.github.fludakit.data.query;

import io.github.fludakit.data.metadata.ResolvedPath;
import io.github.fludakit.data.metadata.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for enriched SortSpec with ResolvedPath support.
 */
class SortSpecResolvedPathTest {

    @Test
    void createSortSpecWithStringProperty() {
        SortSpec spec = SortSpec.of("customer.name", true, false);
        
        assertEquals("customer.name", spec.property());
        assertTrue(spec.ascending());
        assertFalse(spec.ignoreCase());
        assertNull(spec.resolvedPath());
        assertFalse(spec.isResolved());
    }

    @Test
    void createSortSpecWithResolvedPath() {
        TypeDefinition.Prop.Scalar nameProp = new TypeDefinition.Prop.Scalar(
            "name", "java.lang.String", "name", true);
        ResolvedPath path = ResolvedPath.of(nameProp);
        
        SortSpec spec = SortSpec.of(path, true, false);
        
        assertEquals("name", spec.property());
        assertTrue(spec.ascending());
        assertFalse(spec.ignoreCase());
        assertNotNull(spec.resolvedPath());
        assertEquals(path, spec.resolvedPath());
        assertTrue(spec.isResolved());
    }

    @Test
    void createSortSpecWithMultiSegmentResolvedPath() {
        TypeDefinition.Prop.Association customerProp = new TypeDefinition.Prop.Association(
            "customer", "com.example.Customer", null,
            "customer_id", false, false, TypeDefinition.Prop.Association.Cascade.ALL);
        TypeDefinition.Prop.Scalar nameProp = new TypeDefinition.Prop.Scalar(
            "name", "java.lang.String", "name", true);
        ResolvedPath path = ResolvedPath.of(List.of(customerProp, nameProp));
        
        SortSpec spec = SortSpec.of(path, false, true);
        
        assertEquals("customer.name", spec.property());
        assertFalse(spec.ascending());
        assertTrue(spec.ignoreCase());
        assertTrue(spec.isResolved());
    }

    @Test
    void withResolvedPathReturnsNewInstance() {
        SortSpec original = SortSpec.of("name", true, false);
        TypeDefinition.Prop.Scalar nameProp = new TypeDefinition.Prop.Scalar(
            "name", "java.lang.String", "name", true);
        ResolvedPath path = ResolvedPath.of(nameProp);
        
        SortSpec enriched = original.withResolvedPath(path);
        
        assertNotSame(original, enriched);
        assertEquals("name", enriched.property());
        assertEquals(original.ascending(), enriched.ascending());
        assertEquals(original.ignoreCase(), enriched.ignoreCase());
        assertNull(original.resolvedPath());
        assertNotNull(enriched.resolvedPath());
        assertEquals(path, enriched.resolvedPath());
    }

    @Test
    void isResolvedReturnsTrueOnlyWhenPathPresent() {
        SortSpec unresolved = SortSpec.of("name", true, false);
        assertFalse(unresolved.isResolved());
        
        TypeDefinition.Prop.Scalar nameProp = new TypeDefinition.Prop.Scalar(
            "name", "java.lang.String", "name", true);
        ResolvedPath path = ResolvedPath.of(nameProp);
        SortSpec resolved = SortSpec.of(path, true, false);
        assertTrue(resolved.isResolved());
    }

    @Test
    void constructorWithFourParametersWorks() {
        TypeDefinition.Prop.Scalar nameProp = new TypeDefinition.Prop.Scalar(
            "name", "java.lang.String", "name", true);
        ResolvedPath path = ResolvedPath.of(nameProp);
        
        SortSpec spec = new SortSpec("name", true, false, path);
        
        assertEquals("name", spec.property());
        assertTrue(spec.ascending());
        assertFalse(spec.ignoreCase());
        assertEquals(path, spec.resolvedPath());
        assertTrue(spec.isResolved());
    }

    @Test
    void constructorWithNullResolvedPathCreatesUnresolvedSpec() {
        SortSpec spec = new SortSpec("name", true, false, null);
        
        assertEquals("name", spec.property());
        assertTrue(spec.ascending());
        assertFalse(spec.ignoreCase());
        assertNull(spec.resolvedPath());
        assertFalse(spec.isResolved());
    }
}
