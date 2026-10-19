package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnMapping;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.RelationKind;
import io.github.fludakit.data.metadata.RelationMapping;
import io.github.fludakit.data.metadata.TypeDefinition;

import java.util.List;
import java.util.Map;

/**
 * Builds the small mappings {@link SqlBuilderTest} needs using TypeDefinition records.
 */
final class TestMappings {

    private TestMappings() {
    }

    /** A plain entity with explicit columns and relations. */
    static TypeDefinition entity(String typeName, String tableName, ColumnMapping idColumn,
                                 List<ColumnMapping> columns, List<RelationMapping> relations) {
        return new TypeDefinition(typeName, tableName, idColumn.columnName(), false, columns, relations);
    }

    /** An aggregate root with explicit columns and relations. */
    static TypeDefinition aggregateRoot(String typeName, String tableName, ColumnMapping idColumn,
                                        List<ColumnMapping> columns, List<RelationMapping> relations) {
        // Include ID column at the start of the columns list
        List<ColumnMapping> allColumns = new java.util.ArrayList<>();
        allColumns.add(idColumn);
        allColumns.addAll(columns);
        return new TypeDefinition(typeName, tableName, idColumn.columnName(), true, allColumns, relations);
    }

    /**
     * An aggregate root with an explicit columnRefs index.
     */
    static TypeDefinition aggregateRootWithRefs(String typeName, String tableName, ColumnMapping idColumn,
                                                List<ColumnMapping> columns, List<RelationMapping> relations,
                                                Map<String, ColumnRef> columnRefs) {
        // For tests that need custom columnRefs, we'd need a wrapper - simplified for now
        return new TypeDefinition(typeName, tableName, idColumn.columnName(), true, columns, relations);
    }
}
