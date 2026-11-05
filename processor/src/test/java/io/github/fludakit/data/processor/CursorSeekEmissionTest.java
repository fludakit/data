package io.github.fludakit.data.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.fludakit.data.processor.ProcessorHarness.compile;
import static io.github.fludakit.data.processor.ProcessorHarness.sqlFlat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization pins for the generated CURSOR-mode paged finders: the seek SQL constant the
 * emitter registers, and the statement shape the generated body builds from it.
 *
 * <p>These predate the #16 refactor — {@code emitCursorPagedFinder} assembles the seek statement
 * inline, including post-hoc {@code String.replace} surgery on the already-rendered seek predicate
 * for to-many sorts — so the strings below are the BEHAVIOUR we must not change while we move the
 * assembly into core-owned rendering ({@code SqlBuilder#pagedSeekStatement}).
 */
class CursorSeekEmissionTest {

    @TempDir
    Path workspace;

    @Test
    void cursorFinderOverLocalSortSeeksOverTheRenderedShell() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/curs"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-curs"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-curs"));

        Files.writeString(sourceDir.resolve("Widget.java"), """
                package curs;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class Widget {
                    @Identity Long id;
                    String name;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        Files.writeString(sourceDir.resolve("Widgets.java"), """
                package curs;
                import jakarta.data.page.CursoredPage;
                import jakarta.data.page.PageRequest;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Find;
                import jakarta.data.repository.OrderBy;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Widgets extends CrudRepository<Widget, Long> {
                    @Find
                    @OrderBy("name")
                    CursoredPage<Widget> findBy(PageRequest pageRequest);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Widget.java", "Widgets.java");

        String impl = sqlFlat(generatedDir.resolve("curs/Widgets_.java"));
        assertTrue(impl.contains("CURSOR_FIND_BY_SQL = \"SELECT \"id\", \"name\" FROM \"widgets\" WHERE (:cursor0 IS NULL OR ((\"name\" > :cursor0))) ORDER BY \"name\" ASC\""),
                "the local-sort cursor seek must splice keyset seek + declared order over the shell:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("COUNT_CURSOR_FIND_BY_SQL = \"SELECT COUNT(*) FROM \"widgets\"\""),
                "the cursor finder must register the total count constant:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("String sql = CURSOR_FIND_BY_SQL + \" LIMIT :limit\";"),
                "the generated body must fetch one extra row through the seek constant:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"cursor0\", pageRequest.cursor().map(c -> c.get(0)).orElse(null))"),
                "the generated body must bind the cursor key:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void cursorFinderOverToManySortSeeksOverTheWrappedSubquery() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/ordcurs"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-ordcurs"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-ordcurs"));

        Files.writeString(sourceDir.resolve("Line.java"), """
                package ordcurs;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Association;
                import java.util.ArrayList;
                import java.util.List;
                @Entity
                public class Line {
                    @Identity Long id;
                    String product;
                    @Association Order order;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getProduct() { return product; }
                    public void setProduct(String product) { this.product = product; }
                    public Order getOrder() { return order; }
                    public void setOrder(Order order) { this.order = order; }
                }
                """);
        Files.writeString(sourceDir.resolve("Order.java"), """
                package ordcurs;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @AggregateRoot
                public class Order {
                    @Identity Long id;
                    String status;
                    List<Line> lines = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getStatus() { return status; }
                    public void setStatus(String status) { this.status = status; }
                    public List<Line> getLines() { return lines; }
                    public void setLines(List<Line> lines) { this.lines = lines; }
                }
                """);
        Files.writeString(sourceDir.resolve("Orders.java"), """
                package ordcurs;
                import jakarta.data.page.CursoredPage;
                import jakarta.data.page.PageRequest;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Find;
                import jakarta.data.repository.OrderBy;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Orders extends CrudRepository<Order, Long> {
                    @Find
                    @OrderBy("lines.product")
                    CursoredPage<Order> findBy(PageRequest pageRequest);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Line.java", "Order.java", "Orders.java");

        String impl = sqlFlat(generatedDir.resolve("ordcurs/Orders_.java"));
        assertTrue(impl.contains("CURSOR_FIND_BY_SQL = \"SELECT inner_.* FROM (SELECT \"orders\".\"id\", \"orders\".\"status\", MIN(lines_.\"product\") AS \"agg_0\" FROM \"orders\" LEFT JOIN \"lines\" AS lines_ ON lines_.\"order_id\" = \"orders\".\"id\" GROUP BY \"orders\".\"id\", \"orders\".\"status\") AS inner_ WHERE (:cursor0 IS NULL OR ((inner_.\"agg_0\" > :cursor0) OR ((inner_.\"agg_0\" = :cursor0 OR (inner_.\"agg_0\" IS NULL AND :cursor0 IS NULL)) AND inner_.\"id\" > :cursor1))) ORDER BY inner_.\"agg_0\" ASC, inner_.\"id\" ASC\""),
                "the to-many cursor seek must wrap the aggregate subquery and seek on the inner alias:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"cursor1\", pageRequest.cursor().map(c -> c.get(1)).orElse(null))"),
                "the two-key cursor must bind both keys:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("hydrateList(content);"),
                "a to-many cursor read must hydrate the joined collection:"
                        + System.lineSeparator() + impl);
    }
}
