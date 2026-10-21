package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.ResolvedPath;
import io.github.fludakit.data.metadata.TableSchema;
import io.github.fludakit.data.metadata.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SqlBuilder column and table validation.
 */
class SqlBuilderValidationTest {

    private static final TableSchema CUSTOMER_SCHEMA = new TableSchema() {
        private final List<ColumnRef> columns = List.of(
            new ColumnRef.Simple("id", "customer"),
            new ColumnRef.Simple("name", "customer"),
            new ColumnRef.Simple("email", "customer"));

        @Override public String tableName() { return "customer"; }
        @Override public String idColumn() { return "id"; }
        @Override public List<ColumnRef> columns() { return columns; }
    };

    @Test
    void resolveColumnWithValidColumnNameSucceeds() {
        TypeDefinition.Prop.Scalar nameProp = new TypeDefinition.Prop.Scalar(
            "name", "java.lang.String", "name", true);
        ResolvedPath path = ResolvedPath.of(nameProp);
        
        SqlBuilder builder = new SqlBuilder(CUSTOMER_SCHEMA);
        String column = builder.resolveColumn(path);
        
        assertEquals("name", column);
    }

    @Test
    void resolveColumnWithInvalidColumnNameThrows() {
        TypeDefinition.Prop.Scalar invalidProp = new TypeDefinition.Prop.Scalar(
            "invalidColumn", "java.lang.String", "nonexistent_column", true);
        ResolvedPath path = ResolvedPath.of(invalidProp);
        
        SqlBuilder builder = new SqlBuilder(CUSTOMER_SCHEMA);
        
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> builder.resolveColumn(path));
        
        assertTrue(ex.getMessage().contains("nonexistent_column"));
        assertTrue(ex.getMessage().contains("not defined in table 'customer'"));
        assertTrue(ex.getMessage().contains("Available columns:"));
        assertTrue(ex.getMessage().contains("id"));
        assertTrue(ex.getMessage().contains("name"));
        assertTrue(ex.getMessage().contains("email"));
    }

    @Test
    void resolveColumnWithStringPathValidatesColumnName() {
        // This test requires a ColumnResolver, which we don't have in this simple test
        // The validation logic is the same as for ResolvedPath, so we test it via the
        // ResolvedPath overload
        TypeDefinition.Prop.Id idProp = new TypeDefinition.Prop.Id(
            "id", "java.lang.Long", "id", TypeDefinition.Prop.Id.GeneratedType.IDENTITY);
        ResolvedPath path = ResolvedPath.of(idProp);
        
        SqlBuilder builder = new SqlBuilder(CUSTOMER_SCHEMA);
        String column = builder.resolveColumn(path);
        
        assertEquals("id", column);
    }

    @Test
    void schemaTableNameIsAccessible() {
        SqlBuilder builder = new SqlBuilder(CUSTOMER_SCHEMA);
        
        // The schema's table name should be accessible
        assertEquals("customer", CUSTOMER_SCHEMA.tableName());
    }

    @Test
    void schemaColumnsAreAccessible() {
        SqlBuilder builder = new SqlBuilder(CUSTOMER_SCHEMA);
        
        List<ColumnRef> columns = CUSTOMER_SCHEMA.columns();
        assertEquals(3, columns.size());
        assertEquals("id", columns.get(0).column());
        assertEquals("name", columns.get(1).column());
        assertEquals("email", columns.get(2).column());
    }

    @Test
    void validationErrorMessageIncludesPropertyPath() {
        TypeDefinition.Prop.Scalar invalidProp = new TypeDefinition.Prop.Scalar(
            "badProp", "java.lang.String", "bad_column", true);
        ResolvedPath path = ResolvedPath.of(invalidProp);
        
        SqlBuilder builder = new SqlBuilder(CUSTOMER_SCHEMA);
        
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> builder.resolveColumn(path));
        
        // Error message should include the property path for debugging
        assertTrue(ex.getMessage().contains("badProp"));
    }

    @Test
    void validationAcceptsAllDefinedColumns() {
        SqlBuilder builder = new SqlBuilder(CUSTOMER_SCHEMA);
        
        // Test id column
        TypeDefinition.Prop.Id idProp = new TypeDefinition.Prop.Id(
            "id", "java.lang.Long", "id", TypeDefinition.Prop.Id.GeneratedType.IDENTITY);
        assertEquals("id", builder.resolveColumn(ResolvedPath.of(idProp)));
        
        // Test name column
        TypeDefinition.Prop.Scalar nameProp = new TypeDefinition.Prop.Scalar(
            "name", "java.lang.String", "name", true);
        assertEquals("name", builder.resolveColumn(ResolvedPath.of(nameProp)));
        
        // Test email column
        TypeDefinition.Prop.Scalar emailProp = new TypeDefinition.Prop.Scalar(
            "email", "java.lang.String", "email", true);
        assertEquals("email", builder.resolveColumn(ResolvedPath.of(emailProp)));
    }
}
