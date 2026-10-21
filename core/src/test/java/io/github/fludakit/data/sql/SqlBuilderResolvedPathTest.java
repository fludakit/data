package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.ResolvedPath;
import io.github.fludakit.data.metadata.TableSchema;
import io.github.fludakit.data.metadata.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SqlBuilder's ResolvedPath integration.
 */
class SqlBuilderResolvedPathTest {

    private static final TableSchema CUSTOMER_SCHEMA = new TableSchema() {
        private final List<ColumnRef> columns = List.of(
            new ColumnRef.Simple("id", "customer"),
            new ColumnRef.Simple("name", "customer"));

        @Override public String tableName() { return "customer"; }
        @Override public String idColumn() { return "id"; }
        @Override public List<ColumnRef> columns() { return columns; }
    };

    private static final TableSchema ORDER_SCHEMA = new TableSchema() {
        private final List<ColumnRef> columns = List.of(
            new ColumnRef.Simple("id", "order"),
            new ColumnRef.Simple("customer_id", "order"));

        @Override public String tableName() { return "order"; }
        @Override public String idColumn() { return "id"; }
        @Override public List<ColumnRef> columns() { return columns; }
    };

    private static final TypeDefinition.Entity CUSTOMER_ENTITY = (TypeDefinition.Entity) TestMappings.entity(
        "com.example.Customer", "customer",
        new TypeDefinition.Prop.Id("id", "java.lang.Long", "id", TypeDefinition.Prop.Id.GeneratedType.IDENTITY),
        List.of(new TypeDefinition.Prop.Scalar("name", "java.lang.String", "name", true)),
        List.of()
    );

    private static final TypeDefinition.Entity ORDER_ENTITY = (TypeDefinition.Entity) TestMappings.entity(
        "com.example.Order", "order",
        new TypeDefinition.Prop.Id("id", "java.lang.Long", "id", TypeDefinition.Prop.Id.GeneratedType.IDENTITY),
        List.of(),
        List.of(new TypeDefinition.Prop.Association(
            "customer", "com.example.Customer", CUSTOMER_ENTITY,
            "customer_id", false, false, TypeDefinition.Prop.Association.Cascade.ALL))
    );

    @Test
    void resolveColumnWithLocalResolvedPathReturnsColumnName() {
        TypeDefinition.Prop.Scalar nameProp = new TypeDefinition.Prop.Scalar(
            "name", "java.lang.String", "name", true);
        ResolvedPath path = ResolvedPath.of(nameProp);
        
        SqlBuilder builder = new SqlBuilder(CUSTOMER_SCHEMA);
        String column = builder.resolveColumn(path);
        
        assertEquals("name", column);
    }

    @Test
    void resolveColumnWithNonLocalResolvedPathThrows() {
        TypeDefinition.Prop.Scalar nameProp = new TypeDefinition.Prop.Scalar(
            "name", "java.lang.String", "name", true);
        ResolvedPath path = ResolvedPath.of(List.of(
            ORDER_ENTITY.associations().get(0),
            nameProp
        ));
        
        SqlBuilder builder = new SqlBuilder(ORDER_SCHEMA);
        
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> builder.resolveColumn(path));
        assertTrue(ex.getMessage().contains("customer.name"));
        assertTrue(ex.getMessage().contains("traverses a relationship"));
    }

    @Test
    void isToManyPathWithResolvedPathDetectsCollection() {
        TypeDefinition.Prop.EntityCollection ordersProp = new TypeDefinition.Prop.EntityCollection(
            "orders", "java.util.List", ORDER_ENTITY, "customer_id", "customer");
        ResolvedPath path = ResolvedPath.of(ordersProp);
        
        SqlBuilder builder = new SqlBuilder(CUSTOMER_SCHEMA);
        
        assertTrue(builder.isToManyPath(path));
    }

    @Test
    void isToManyPathWithResolvedPathReturnsFalseForScalar() {
        TypeDefinition.Prop.Scalar nameProp = new TypeDefinition.Prop.Scalar(
            "name", "java.lang.String", "name", true);
        ResolvedPath path = ResolvedPath.of(nameProp);
        
        SqlBuilder builder = new SqlBuilder(CUSTOMER_SCHEMA);
        
        assertFalse(builder.isToManyPath(path));
    }

    @Test
    void resolveColumnWithIdPropertyReturnsColumnName() {
        TypeDefinition.Prop.Id idProp = new TypeDefinition.Prop.Id(
            "id", "java.lang.Long", "id", TypeDefinition.Prop.Id.GeneratedType.IDENTITY);
        ResolvedPath path = ResolvedPath.of(idProp);
        
        SqlBuilder builder = new SqlBuilder(CUSTOMER_SCHEMA);
        String column = builder.resolveColumn(path);
        
        assertEquals("id", column);
    }
}
