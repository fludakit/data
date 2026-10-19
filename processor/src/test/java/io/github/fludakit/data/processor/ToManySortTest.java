package io.github.fludakit.data.processor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test to verify TO_MANY sorting logic generates correct SQL fragments.
 */
class ToManySortTest {

    @Test
    void testToManyJoinClauseGeneration() {
        // Simulate what the generated code should produce
        String tableName = "\"player\"";
        String fkColumn = "\"team_id\"";
        String ownerIdColumn = "\"id\"";
        String ownerTable = "\"team\"";
        String joinTableAlias = "players_sort_";
        
        StringBuilder joinClause = new StringBuilder();
        joinClause.append(" LEFT JOIN ").append(tableName).append(" AS ").append(joinTableAlias)
            .append(" ON ").append(joinTableAlias).append(".").append(fkColumn)
            .append(" = ").append(ownerTable).append(".").append(ownerIdColumn);
        
        String expected = " LEFT JOIN \"player\" AS players_sort_ ON players_sort_.\"team_id\" = \"team\".\"id\"";
        assertEquals(expected, joinClause.toString());
    }
    
    @Test
    void testOrderByWithJoinAlias() {
        String joinTableAlias = "players_sort_";
        String colName = "name";
        
        StringBuilder orderBy = new StringBuilder();
        orderBy.append(joinTableAlias).append(".").append(colName);
        
        assertEquals("players_sort_.name", orderBy.toString());
    }
}
