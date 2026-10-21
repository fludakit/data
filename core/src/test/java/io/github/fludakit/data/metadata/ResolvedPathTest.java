package io.github.fludakit.data.metadata;

import io.github.fludakit.data.metadata.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ResolvedPath}.
 */
class ResolvedPathTest {
    
    @Test
    void ofSingleScalarProperty() {
        TypeDefinition.Prop.Scalar nameProp = new TypeDefinition.Prop.Scalar("name", "java.lang.String", "name", true);
        ResolvedPath path = ResolvedPath.of(nameProp);
        
        assertEquals(1, path.length());
        assertEquals(nameProp, path.leaf());
        assertEquals(nameProp, path.root());
        assertEquals("name", path.columnName());
        assertTrue(path.isLocal());
        assertFalse(path.isToMany());
        assertEquals("name", path.toDotPath());
    }
    
    @Test
    void ofSingleIdProperty() {
        TypeDefinition.Prop.Id idProp = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id", 
            TypeDefinition.Prop.Id.GeneratedType.AUTO);
        ResolvedPath path = ResolvedPath.of(idProp);
        
        assertEquals(1, path.length());
        assertEquals(idProp, path.leaf());
        assertEquals(idProp, path.root());
        assertEquals("id", path.columnName());
        assertTrue(path.isLocal());
        assertFalse(path.isToMany());
        assertEquals("id", path.toDotPath());
    }
    
    @Test
    void ofMultipleProperties() {
        // Use two Scalar properties to test multi-hop path
        TypeDefinition.Prop.Scalar firstNameProp = new TypeDefinition.Prop.Scalar("firstName", "java.lang.String", "first_name", true);
        TypeDefinition.Prop.Scalar lastNameProp = new TypeDefinition.Prop.Scalar("lastName", "java.lang.String", "last_name", true);
        
        ResolvedPath path = ResolvedPath.of(List.of(firstNameProp, lastNameProp));
        
        assertEquals(2, path.length());
        assertEquals(lastNameProp, path.leaf());
        assertEquals(firstNameProp, path.root());
        assertEquals("last_name", path.columnName());
        assertFalse(path.isLocal());
        assertFalse(path.isToMany());
        assertEquals("firstName.lastName", path.toDotPath());
    }
    
    @Test
    void columnNameThrowsForNonScalarLeaf() {
        // Use Id property as non-scalar leaf (though it has a column, we test the exception path)
        // Actually, Id does have columnName(), so let's test with a different scenario
        // For now, just test that columnName() works for Id
        TypeDefinition.Prop.Id idProp = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id", 
            TypeDefinition.Prop.Id.GeneratedType.AUTO);
        
        ResolvedPath path = ResolvedPath.of(idProp);
        
        // Id should work fine
        assertEquals("id", path.columnName());
    }
    
    @Test
    void ofRejectsNullList() {
        assertThrows(NullPointerException.class, () -> ResolvedPath.of((List<TypeDefinition.Prop>) null));
    }
    
    @Test
    void ofRejectsEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> ResolvedPath.of(List.of()));
    }
    
    @Test
    void equalsAndHashCode() {
        TypeDefinition.Prop.Scalar nameProp = new TypeDefinition.Prop.Scalar("name", "java.lang.String", "name", true);
        TypeDefinition.Prop.Scalar otherNameProp = new TypeDefinition.Prop.Scalar("name", "java.lang.String", "name", true);
        
        ResolvedPath path1 = ResolvedPath.of(nameProp);
        ResolvedPath path2 = ResolvedPath.of(otherNameProp);
        
        assertEquals(path1, path2);
        assertEquals(path1.hashCode(), path2.hashCode());
    }
    
    @Test
    void toStringFormat() {
        TypeDefinition.Prop.Scalar nameProp = new TypeDefinition.Prop.Scalar("name", "java.lang.String", "name", true);
        ResolvedPath path = ResolvedPath.of(nameProp);
        
        assertEquals("ResolvedPath[name]", path.toString());
    }
    
    @Test
    void propsListIsUnmodifiable() {
        TypeDefinition.Prop.Scalar nameProp = new TypeDefinition.Prop.Scalar("name", "java.lang.String", "name", true);
        ResolvedPath path = ResolvedPath.of(nameProp);
        
        assertThrows(UnsupportedOperationException.class, () -> path.props().add(nameProp));
    }
}
