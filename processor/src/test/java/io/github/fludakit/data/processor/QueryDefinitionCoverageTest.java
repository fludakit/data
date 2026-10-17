package io.github.fludakit.data.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.fludakit.data.processor.ProcessorHarness.compile;
import static io.github.fludakit.data.processor.ProcessorHarness.sqlFlat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every repository method publishes a {@link io.github.fludakit.data.query.QueryDefinition} in the
 * generated {@code <Repository>Queries} holder: the CRUD built-ins and lifecycle writes inherited from
 * {@code CrudRepository}, and a native {@code @Query} statement. The finder shapes are covered by the
 * query-by-method-name and read-projection suites; this test pins the shapes those suites never reach.
 */
class QueryDefinitionCoverageTest {

    @TempDir
    Path workspace;

    @Test
    void publishesNativeAndCrudDefinitions() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/cover"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-cover"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-cover"));

        Files.writeString(sourceDir.resolve("Item.java"), """
                package cover;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class Item {
                    @Identity Long id;
                    String name;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        Files.writeString(sourceDir.resolve("Items.java"), """
                package cover;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Param;
                import jakarta.data.repository.Query;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Items extends CrudRepository<Item, Long> {
                    @Query("SELECT * FROM item WHERE name = :name")
                    Item byName(@Param("name") String name);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Item.java", "Items.java");

        String queries = sqlFlat(generatedDir.resolve("cover/ItemsQueries.java"));
        assertTrue(queries.contains("BY_NAME_QUERY = new QueryDefinition.Native("),
                "a @Query method publishes a Native definition:" + System.lineSeparator() + queries);
        assertTrue(queries.contains("SAVE_QUERY = new QueryDefinition.Upsert("),
                "the inherited save publishes an Upsert definition:" + System.lineSeparator() + queries);
        assertTrue(queries.contains("INSERT_QUERY = new QueryDefinition.Insert("),
                "the inherited insert publishes an Insert definition:" + System.lineSeparator() + queries);
        assertTrue(queries.contains("UPDATE_QUERY = new QueryDefinition.Update("),
                "the inherited update publishes an Update definition:" + System.lineSeparator() + queries);
        assertTrue(queries.contains("DELETE_BY_ID_QUERY = new QueryDefinition.DeleteById("),
                "the inherited deleteById publishes a DeleteById definition:" + System.lineSeparator() + queries);
        assertTrue(queries.contains("FIND_BY_ID_QUERY = new QueryDefinition.Read("),
                "the inherited findById publishes a Read definition:" + System.lineSeparator() + queries);
    }
}
