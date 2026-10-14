package io.github.fludakit.data.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static io.github.fludakit.data.processor.ProcessorHarness.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the CRUD WRITE bodies: {@code save}, {@code insert}, {@code update} and their batch forms, plus
 * every child statement they cascade into — element-collection child tables, owned entity
 * collections and to-one composition targets.
 *
 * <p>Split out of {@code RepositoryProcessorTest}; the shared compile-and-assert plumbing
 * lives in {@link ProcessorHarness}.</p>
 */
class WriteEmissionTest {

    @TempDir
    Path workspace;

    @Test
    void anElementCollectionInsertWritesTheParentFkAndEveryFlattenedLeafColumn() throws Exception {
        // The SELECT side is already pinned to EXCLUDE the parent fk from hydration; the INSERT side
        // carries the opposite rule and was never pinned — the fk is a column of the child table and
        // must be written. The column list and the params must also come from the SAME source: they
        // used to diverge (properties vs flattened descriptor leaves), which emitted a placeholder
        // nobody bound.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/element-insert"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-element-insert"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-element-insert"));

        Files.writeString(sourceDir.resolve("Tag.java"), """
                package elementinsert;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Tag {
                    String label;
                    long weight;
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                    public long getWeight() { return weight; }
                    public void setWeight(long weight) { this.weight = weight; }
                }
                """);
        Files.writeString(sourceDir.resolve("Article.java"), """
                package elementinsert;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @Entity
                public class Article {
                    @Identity Long id;
                    String title;
                    List<Tag> tags = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public List<Tag> getTags() { return tags; }
                    public void setTags(List<Tag> tags) { this.tags = tags; }
                }
                """);
        Files.writeString(sourceDir.resolve("Articles.java"), """
                package elementinsert;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Articles extends CrudRepository<Article, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Tag.java", "Article.java", "Articles.java");

        String impl = sqlFlat(generatedDir.resolve("elementinsert/ArticlesImpl.java"));
        // Parent fk FIRST — the opposite of the hydration SELECT, which drops it — then the leaves.
        assertTrue(impl.contains("SQL_INSERT_TAGS = \"INSERT INTO \"article_tags\" (\"article_id\", \"label\","
                        + " \"weight\") VALUES (:article_id, :label, :weight)\""),
                "the element-collection INSERT must list the parent fk and every leaf column:"
                        + System.lineSeparator() + impl);
        // …and bind exactly those placeholders: the fk from the ROOT id, the leaves from the item.
        assertTrue(impl.contains("jdbcClient.sql(SQL_INSERT_TAGS).param(\"article_id\", entity.getId())"
                        + ".param(\"label\", item.getLabel())"
                        + ".param(\"weight\", item.getWeight()).update()"),
                "every listed column must be bound, in the same order:" + System.lineSeparator() + impl);
    }

    @Test
    void twoDifferentStatementsSharingABaseNameGetDistinctConstants() throws Exception {
        // The registry renames silently: a base that is already taken by DIFFERENT SQL comes back as
        // <base>_2. Nothing else would notice — the caller emits whatever name it is handed — so this
        // is the only place the rename is observable. Overloaded @Query methods are the one source
        // shape that reaches it: the base is derived from the method name, which overloads share.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/colliding-constants"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-colliding-constants"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-colliding-constants"));

        Files.writeString(sourceDir.resolve("Book.java"), """
                package collidingconstants;
                public class Book {
                    public Long id;
                    public String title;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                }
                """);
        Files.writeString(sourceDir.resolve("Shelf.java"), """
                package collidingconstants;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Query;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Shelf extends CrudRepository<Book, Long> {
                    @Query("SELECT id, title FROM book WHERE title = :title")
                    List<Book> search(String title);

                    @Query("SELECT id, title FROM book WHERE id = :id")
                    List<Book> search(Long id);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Book.java", "Shelf.java");

        String impl = Files.readString(generatedDir.resolve("collidingconstants/ShelfImpl.java"));
        assertTrue(impl.contains("SQL_SEARCH = \"SELECT id, title FROM book WHERE title = :title\""),
                "the first statement must keep the bare base name:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("SQL_SEARCH_2 = \"SELECT id, title FROM book WHERE id = :id\""),
                "a second, different statement must be renamed rather than overwrite the first:"
                        + System.lineSeparator() + impl);
        // Both must still be USED, each by its own method: a rename that left one caller pointing at
        // the other's SQL would compile and return the wrong rows.
        assertTrue(statementOf(impl, "SQL_SEARCH_2)").contains(".param(\"id\", id)"),
                "the renamed constant must be used by the overload that declares it:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void aValueObjectNestedInAnElementCollectionValueObjectFlattensLikeAnEmbeddedOne() throws Exception {
        // The child-table INSERT iterates the element-collection DESCRIPTOR's columns and binds each
        // through the descriptor path, while <VO>RowMapper reads the flattened labels off the value
        // object's own metadata. Building the nested value object as ONE Scalar per property made the
        // two disagree: the INSERT bound the whole object to a single "point" column while the mapper
        // read "point_lat"/"point_lon", so the row written was never the row read back. Both sides now
        // flatten, exactly as the same value object embedded in an entity already did
        // (address_point_lat). compile() also compiles the generated sources, so this run is itself the
        // proof that item.getPoint().getLat() type-checks against the fixture.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/ec-nested-vo"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-ec-nested-vo"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-ec-nested-vo"));
        Files.writeString(sourceDir.resolve("Album.java"), """
                package ecnestedvo;
                import java.util.List;
                public class Album {
                    Long id;
                    String title;
                    List<Track> tracks;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public List<Track> getTracks() { return tracks; }
                    public void setTracks(List<Track> tracks) { this.tracks = tracks; }
                }
                """);
        Files.writeString(sourceDir.resolve("Track.java"), """
                package ecnestedvo;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Track {
                    String sku;
                    Point point;
                    public String getSku() { return sku; }
                    public void setSku(String sku) { this.sku = sku; }
                    public Point getPoint() { return point; }
                    public void setPoint(Point point) { this.point = point; }
                }
                """);
        Files.writeString(sourceDir.resolve("Point.java"), """
                package ecnestedvo;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Point {
                    double lat;
                    double lon;
                    public double getLat() { return lat; }
                    public void setLat(double lat) { this.lat = lat; }
                    public double getLon() { return lon; }
                    public void setLon(double lon) { this.lon = lon; }
                }
                """);
        Files.writeString(sourceDir.resolve("Albums.java"), """
                package ecnestedvo;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Albums extends CrudRepository<Album, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Album.java", "Track.java", "Point.java", "Albums.java");

        String impl = sqlFlat(generatedDir.resolve("ecnestedvo/AlbumsImpl.java"));
        assertTrue(impl.contains("INSERT INTO \"album_tracks\" (\"album_id\", \"sku\", \"point_lat\","
                        + " \"point_lon\")"),
                "a value object nested in an element-collection value object must flatten to one column"
                        + " per leaf, not one per property:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"point_lat\", item.getPoint() == null ? null"
                        + " : item.getPoint().getLat())"),
                "the flattened leaf must bind through the value-object path:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void valueObjectAssociationChildBindsFkIdInElementCollectionInsert() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/voca"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-voca"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-voca"));

        Files.writeString(sourceDir.resolve("Product.java"), """
                package voca;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Product {
                    @Identity Long id;
                    String name;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        // A @ValueObject carrying an @Association field: the child-table classifier
        // (RepositoryProcessor.valueObjectMetadata) must treat it as ASSOCIATION (global contract),
        // and buildCollection must flag BOTH MANY_TO_ONE and ASSOCIATION as association-backed so
        // the fk binds item.getProduct().getId(). Changing only one of the two sites breaks this
        // binding silently; this test is the coupled-pair regression lock (mirrors the IT corpus'
        // OrderItem/Order element collection).
        Files.writeString(sourceDir.resolve("Line.java"), """
                package voca;
                import io.github.fludakit.data.annotation.Association;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Line {
                    @Association Product product;
                    int quantity;
                    public Product getProduct() { return product; }
                    public void setProduct(Product product) { this.product = product; }
                    public int getQuantity() { return quantity; }
                    public void setQuantity(int quantity) { this.quantity = quantity; }
                }
                """);
        Files.writeString(sourceDir.resolve("Basket.java"), """
                package voca;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @AggregateRoot
                public class Basket {
                    @Identity Long id;
                    List<Line> lines = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public List<Line> getLines() { return lines; }
                    public void setLines(List<Line> lines) { this.lines = lines; }
                }
                """);
        Files.writeString(sourceDir.resolve("Baskets.java"), """
                package voca;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Baskets extends CrudRepository<Basket, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Product.java", "Line.java", "Basket.java", "Baskets.java");

        String impl = Files.readString(generatedDir.resolve("voca/BasketsImpl.java")).replace("\\\"", "\"");
        assertTrue(impl.contains("INSERT INTO \"basket_lines\" (\"basket_id\", \"product_id\", \"quantity\")"
                        + " VALUES (:basket_id, :product_id, :quantity)"),
                "child-table insert must keep the fk column:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"product_id\", item.getProduct() == null ? null : item.getProduct().getId())"),
                "@Association child column must bind the target's id, not the target object:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"quantity\", item.getQuantity())"),
                "scalar child column must bind the getter directly:" + System.lineSeparator() + impl);
    }

    @Test
    void elementCollectionIsHydratedOnReadAndReplacedOnUpdate() throws Exception {
        // Reuses the voca element-collection shape (@Association child + primitive scalar) so the
        // hydration SELECT list is guarded by the same column classification the insert test pins.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/hyd"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-hyd"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-hyd"));

        Files.writeString(sourceDir.resolve("Product.java"), """
                package hyd;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Product {
                    @Identity Long id;
                    String name;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        Files.writeString(sourceDir.resolve("Line.java"), """
                package hyd;
                import io.github.fludakit.data.annotation.Association;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Line {
                    @Association Product product;
                    int quantity;
                    public Product getProduct() { return product; }
                    public void setProduct(Product product) { this.product = product; }
                    public int getQuantity() { return quantity; }
                    public void setQuantity(int quantity) { this.quantity = quantity; }
                }
                """);
        Files.writeString(sourceDir.resolve("Basket.java"), """
                package hyd;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @AggregateRoot
                public class Basket {
                    @Identity Long id;
                    String name;
                    List<Line> lines = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public List<Line> getLines() { return lines; }
                    public void setLines(List<Line> lines) { this.lines = lines; }
                }
                """);
        Files.writeString(sourceDir.resolve("Baskets.java"), """
                package hyd;
                import jakarta.data.repository.By;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Find;
                import jakarta.data.repository.Repository;
                import java.util.List;
                import java.util.Optional;
                import java.util.stream.Stream;
                @Repository
                public interface Baskets extends CrudRepository<Basket, Long> {
                    @Find List<Basket> findByName(@By("name") String name);
                    @Find Stream<Basket> streamByName(@By("name") String name);
                    @Find Optional<Basket> optionalByName(@By("name") String name);
                    @Find Basket singleByName(@By("name") String name);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Product.java", "Line.java", "Basket.java", "Baskets.java");

        String impl = Files.readString(generatedDir.resolve("hyd/BasketsImpl.java")).replace("\\\"", "\"");

        // A per-collection loader plus its SELECT constant and a delete constant for replace-all/cascade.
        assertTrue(impl.contains("SQL_SELECT_LINES"), "missing child SELECT constant:" + System.lineSeparator() + impl);
        // The emitted hydration SELECT is pinned as an exact constant string, not by shape: the
        // ASSOCIATION `product` column is excluded, so the list is parentFk + the flattened SCALAR
        // `quantity`. The loose `IN (` token alone would also match a wrong column list.
        // The file's `\"` escapes are normalised to `"` by the read above, so the expected literal uses plain quotes.
        assertTrue(impl.contains("SQL_SELECT_LINES = \"SELECT \"basket_id\", \"quantity\" FROM "
                        + "\"basket_lines\" WHERE \"basket_id\" IN (\";"),
                "hydration SELECT constant value must be pinned:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("IN ("), "loader must build a runtime IN (:key0, ...) list:" + System.lineSeparator() + impl);
        // NOTE (ruling #7): JavaPoet's skipJavaLangImports still IMPORTS java.util.Collection, so the
        // generated loader/umbrella parameter renders as the simple `Collection<Basket>` (an
        // `import java.util.Collection;` is added). The normative source of truth is the clean generated
        // code; the string assertion is corrected here to match the real form. The raw-string expression
        // passed to the umbrella ($L, not $T) stays fully-qualified `java.util.List.of(e)`, so its
        // assertion below is left verbatim.
        assertTrue(impl.contains("private void hydrateLines(Collection<Basket> parents)"),
                "missing per-collection hydration loader:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("new LineRowMapper().mapRow(rs, rowNum)"),
                "loader must pair rows through the generated VO row mapper:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("parent.setLines("), "loader must group children onto each parent:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("SQL_DELETE_LINES"), "missing child DELETE constant:" + System.lineSeparator() + impl);

        // The umbrella is emitted once and reached from every read shape.
        assertTrue(impl.contains("private void hydrateAll(Collection<Basket> parents)"),
                "missing umbrella hydrateAll:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("hydrateLines(parents)"),
                "umbrella must call each loader:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("found.ifPresent(e -> hydrateAll(java.util.List.of(e)))"),
                "findById must hydrate through the umbrella:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("hydrateAll(content)"),
                "paged read must hydrate the materialized content list:" + System.lineSeparator() + impl);

        // The hydrated @Find branches (LIST / STREAM / OPTIONAL / SINGLE) each wrap the umbrella.
        assertTrue(impl.contains("List<Basket> results = jdbcClient.sql(SQL_FIND_BY_NAME)"
                        + ".param(\"name\", name).query(new BasketRowMapper()).list();\n"
                        + "    hydrateAll(results);\n"
                        + "    return results;"),
                "hydrated LIST @Find must materialize, hydrateAll, then return:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("List<Basket> results = jdbcClient.sql(SQL_STREAM_BY_NAME)"
                        + ".param(\"name\", name).query(new BasketRowMapper()).list();\n"
                        + "    hydrateAll(results);\n"
                        + "    return results.stream();"),
                "hydrated STREAM @Find must materialize, hydrateAll, then stream:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("Optional<Basket> found = jdbcClient.sql(SQL_OPTIONAL_BY_NAME)"
                        + ".param(\"name\", name).query(new BasketRowMapper()).optional();\n"
                        + "    found.ifPresent(e -> hydrateAll(java.util.List.of(e)));\n"
                        + "    return found;"),
                "hydrated OPTIONAL @Find must hydrateAll the present value:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("Basket found = jdbcClient.sql(SQL_SINGLE_BY_NAME)"
                        + ".param(\"name\", name).query(new BasketRowMapper()).single();\n"
                        + "    hydrateAll(java.util.List.of(found));\n"
                        + "    return found;"),
                "hydrated SINGLE @Find must hydrateAll the single value:"
                        + System.lineSeparator() + impl);

        // Replace-all update: children deleted by parent fk (child param stays COLUMN-named), then re-inserted.
        assertTrue(impl.contains(".param(\"basket_id\", entity.getId())"),
                "update must delete children by the parent fk before re-inserting:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("DELETE FROM \"basket_lines\" WHERE \"basket_id\" = :basket_id"),
                "delete-cascade constant:" + System.lineSeparator() + impl);

        // The generated VO row mapper reads the primitive leaf with the wasNull guard and never touches
        // the @Association target: a many-to-one/association child is left null, never hydrated.
        String voMapper = Files.readString(generatedDir.resolve("hyd/LineRowMapper.java"));
        assertTrue(voMapper.contains("rs.getInt(LineMetadata.QUANTITY)"),
                "primitive leaf must use rs.getInt(...) with the VO's own metadata:" + System.lineSeparator() + voMapper);
        assertTrue(voMapper.contains("if (rs.wasNull())"),
                "primitive read must guard wasNull:" + System.lineSeparator() + voMapper);
        assertTrue(!voMapper.contains("getProduct"),
                "@Association child target must never be hydrated:" + System.lineSeparator() + voMapper);

        assertTrue(!impl.contains("java.lang.reflect"), "generated code must stay reflection-free:" + System.lineSeparator() + impl);
    }

    @Test
    void cascadeCoversGateFailedCollectionWithoutLoader() throws Exception {
        // save() inserts child rows for EVERY collection (appendInsertAndChildCascades drives from
        // entity.collections(), not from the aggregate-root gate), so update() and deleteById() must
        // cascade the DELETE for every collection TOO — even one whose VO fails the row-mapper gate.
        // Gate failure may only degrade HYDRATION (no loader, no umbrella), never cascade integrity,
        // else the child rows orphan.
        // A VO with a non-JDBC-safe enum property fails canGenerateRowMapper, exercising that path.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/casc"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-casc"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-casc"));

        Files.writeString(sourceDir.resolve("Line.java"), """
                package casc;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Line {
                    int quantity;
                    java.time.Duration flag;
                    public int getQuantity() { return quantity; }
                    public void setQuantity(int quantity) { this.quantity = quantity; }
                    public java.time.Duration getFlag() { return flag; }
                    public void setFlag(java.time.Duration flag) { this.flag = flag; }
                }
                """);
        Files.writeString(sourceDir.resolve("Basket.java"), """
                package casc;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @AggregateRoot
                public class Basket {
                    @Identity Long id;
                    List<Line> lines = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public List<Line> getLines() { return lines; }
                    public void setLines(List<Line> lines) { this.lines = lines; }
                }
                """);
        Files.writeString(sourceDir.resolve("Baskets.java"), """
                package casc;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Baskets extends CrudRepository<Basket, Long> { }
                """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(
                sourceDir, classesDir, generatedDir, "Line.java", "Basket.java", "Baskets.java");

        String impl = Files.readString(generatedDir.resolve("casc/BasketsImpl.java")).replace("\\\"", "\"");

        // Save still inserts child rows for the gate-failed collection (unchanged write side).
        assertTrue(impl.contains("INSERT INTO \"basket_lines\""),
                "child rows must be inserted for every collection:" + System.lineSeparator() + impl);

        // The DELETE cascade (replace-all update + deleteById) is emitted even for the gate-failed
        // collection: the cascade keeps the child rows from orphaning.
        assertTrue(impl.contains("SQL_DELETE_LINES"),
                "gate-failed collection must still get its child DELETE constant:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("DELETE FROM \"basket_lines\" WHERE \"basket_id\" = :basket_id"),
                "gate-failed collection must still get the child DELETE SQL:" + System.lineSeparator() + impl);

        // Hydration degrades: no loader SELECT, no hydrateLines loader, no umbrella, no VO row mapper.
        assertTrue(!impl.contains("SQL_SELECT_LINES"),
                "no hydration SELECT must be emitted for a gate-failed collection:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("hydrateLines"),
                "no loader must be emitted for a gate-failed collection:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("hydrateAll"),
                "no umbrella when nothing hydrates:" + System.lineSeparator() + impl);
        assertTrue(Files.notExists(generatedDir.resolve("casc/LineRowMapper.java")),
                "gate-failed VO must not get a row mapper");

        // The gate skip is still recorded as a NOTE rather than failing the round.
        String notes = notesOf(diagnostics);
        assertTrue(notes.contains("not reflection-free mappable"),
                "gate-skip NOTE must be recorded:" + System.lineSeparator() + notes);
    }

    @Test
    void collectionLoaderKeyFollowsIntegerIdType() throws Exception {
        // The collection loader must derive the parent-key / child-FK type from the entity id,
        // not hardcode Long. An Integer-id entity with an element collection would emit a loader using
        // List<Long> keys / getObject(fk, Long.class) / (Long) row[0] / Map<Long, ...> and fail to
        // compile in the consumer build. The compile step below is the RED proof that predates the
        // fix (the generated
        // impl does not compile); the assertions below are the structural lock on the derived key type.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/ihd"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-ihd"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-ihd"));

        Files.writeString(sourceDir.resolve("Line.java"), """
                package ihd;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Line {
                    int quantity;
                    public int getQuantity() { return quantity; }
                    public void setQuantity(int quantity) { this.quantity = quantity; }
                }
                """);
        Files.writeString(sourceDir.resolve("Basket.java"), """
                package ihd;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @AggregateRoot
                public class Basket {
                    @Identity Integer id;
                    List<Line> lines = new ArrayList<>();
                    public Integer getId() { return id; }
                    public void setId(Integer id) { this.id = id; }
                    public List<Line> getLines() { return lines; }
                    public void setLines(List<Line> lines) { this.lines = lines; }
                }
                """);
        Files.writeString(sourceDir.resolve("Baskets.java"), """
                package ihd;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Baskets extends CrudRepository<Basket, Integer> { }
                """);

        // A gate-passing VO so a loader IS emitted; the loader key type must follow the Integer id.
        // The generated-key coercion is id-aware, so the Integer-id impl compiles FULLY. The
        // residual-error canary below is what pins that: it was written to expect the OLD
        // `longValue()` coercion, which failed the whole compile, and is now flipped to assert the
        // compile succeeds.
        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(
                sourceDir, classesDir, generatedDir, "Line.java", "Basket.java", "Baskets.java");

        String impl = Files.readString(generatedDir.resolve("ihd/BasketsImpl.java"))
                .replace("\\\"", "\"")
                .replaceAll("\\s+", " ");

        // The FK read, the grouping cast, the key list and the grouped map all carry Integer.
        assertTrue(impl.contains("rs.getObject(\"basket_id\", Integer.class)"),
                "loader must read the FK as the id type:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("(Integer) row[0]"),
                "loader must cast the pairing key to the id type:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("List<Integer> keys"),
                "loader key list must use the id type:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("Map<Integer, List<Line>> grouped"),
                "loader grouped map must key on the id type:" + System.lineSeparator() + impl);
        // And must NOT retain the hardcoded Long key.
        assertTrue(!impl.contains("rs.getObject(\"basket_id\", Long.class)"),
                "loader must not hardcode Long:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("List<Long> keys"),
                "loader must not hardcode List<Long>:" + System.lineSeparator() + impl);

        // Id-aware generated-key coercion. Integer id now binds intValue(), not longValue().
        assertTrue(impl.contains("entity.setId(((Number) keys.getKey()).intValue())"),
                "insert coercion must follow the id type:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("longValue"),
                "Integer id must not use the Long coercion:" + System.lineSeparator() + impl);

        // The whole impl now compiles: the loader AND the previously-blocking insert coercion are both
        // id-correct, so there are no residual javac ERRORs (the flipped canary).
        List<Diagnostic<? extends JavaFileObject>> errors = diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .collect(Collectors.toList());
        assertTrue(errors.isEmpty(),
                "Integer-id impl must now fully compile, got:" + System.lineSeparator() + errors);
    }

    @Test
    void appAssignedIdInsertBindsIdParameterWithoutKeyholderRoundtrip() throws Exception {
        // Generated-key coercion is type-driven. String/UUID ids are
        // application-assigned, so the generated INSERT must BIND the id column parameter and skip the
        // GeneratedKeyHolder/getKey roundtrip entirely — the old longValue() fallback was runtime-broken
        // for them (and did not compile at all in a repository impl: that compile failure IS the pre-fix
        // RED proof, surfaced by compileWithDiagnostics below). Long ids keep the keyholder shape
        // unchanged (this pin folds whitespace/escapes; exact byte parity is carried by the IT
        // corpus gate), and save() on a non-keyholder id NOTE-degrades
        // ("id type <X> requires application-assigned identifiers") while the compile continues.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/appkey"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-appkey"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-appkey"));

        Files.writeString(sourceDir.resolve("Ticket.java"), """
                package appkey;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class Ticket {
                    @Identity String id;
                    String label;
                    public String getId() { return id; }
                    public void setId(String id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                }
                """);
        Files.writeString(sourceDir.resolve("TicketRepository.java"), """
                package appkey;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface TicketRepository extends CrudRepository<Ticket, String> { }
                """);
        Files.writeString(sourceDir.resolve("Badge.java"), """
                package appkey;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.UUID;
                @Entity
                public class Badge {
                    @Identity UUID id;
                    String serial;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getSerial() { return serial; }
                    public void setSerial(String serial) { this.serial = serial; }
                }
                """);
        Files.writeString(sourceDir.resolve("BadgeRepository.java"), """
                package appkey;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.UUID;
                @Repository
                public interface BadgeRepository extends CrudRepository<Badge, UUID> { }
                """);
        Files.writeString(sourceDir.resolve("Widget.java"), """
                package appkey;
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
        Files.writeString(sourceDir.resolve("WidgetRepository.java"), """
                package appkey;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface WidgetRepository extends CrudRepository<Widget, Long> { }
                """);
        Files.writeString(sourceDir.resolve("Segment.java"), """
                package appkey;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class Segment {
                    @Identity Short id;
                    String code;
                    public Short getId() { return id; }
                    public void setId(Short id) { this.id = id; }
                    public String getCode() { return code; }
                    public void setCode(String code) { this.code = code; }
                }
                """);
        Files.writeString(sourceDir.resolve("SegmentRepository.java"), """
                package appkey;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface SegmentRepository extends CrudRepository<Segment, Short> { }
                """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(
                sourceDir, classesDir, generatedDir,
                "Ticket.java", "TicketRepository.java", "Badge.java", "BadgeRepository.java",
                "Widget.java", "WidgetRepository.java", "Segment.java", "SegmentRepository.java");

        String ticket = Files.readString(generatedDir.resolve("appkey/TicketRepositoryImpl.java"))
                .replace("\\\"", "\"").replaceAll("\\s+", " ");
        // No-keyholder shape: the identity column is part of the INSERT and bound from the entity.
        assertTrue(ticket.contains("SQL_INSERT = \"INSERT INTO \"ticket\" (\"id\", \"label\") VALUES (:id, :label)\""),
                "the app-assigned id must be an INSERT column:" + System.lineSeparator() + ticket);
        assertTrue(ticket.contains(
                        "jdbcClient.sql(SQL_INSERT).param(\"id\", entity.getId()).param(\"label\", entity.getLabel()).update()"),
                "the pre-assigned id parameter must be bound and the update must skip the keyholder:"
                        + System.lineSeparator() + ticket);
        assertTrue(!ticket.contains("longValue"),
                "String id must not use the Number coercion:" + System.lineSeparator() + ticket);
        assertTrue(!ticket.contains("GeneratedKeyHolder") && !ticket.contains("keys.getKey()"),
                "String id must not do the keyholder roundtrip:" + System.lineSeparator() + ticket);

        String badge = Files.readString(generatedDir.resolve("appkey/BadgeRepositoryImpl.java"))
                .replace("\\\"", "\"").replaceAll("\\s+", " ");
        assertTrue(badge.contains("SQL_INSERT = \"INSERT INTO \"badge\" (\"id\", \"serial\") VALUES (:id, :serial)\""),
                "the app-assigned UUID id must be an INSERT column:" + System.lineSeparator() + badge);
        assertTrue(badge.contains(
                        "jdbcClient.sql(SQL_INSERT).param(\"id\", entity.getId()).param(\"serial\", entity.getSerial()).update()"),
                "the pre-assigned UUID id parameter must be bound:" + System.lineSeparator() + badge);
        assertTrue(!badge.contains("longValue"),
                "UUID id must not use the Number coercion:" + System.lineSeparator() + badge);
        assertTrue(!badge.contains("GeneratedKeyHolder") && !badge.contains("keys.getKey()"),
                "UUID id must not do the keyholder roundtrip:" + System.lineSeparator() + badge);

        // Long-id byte parity: the keyholder emission is textually identical to the shape it had
        // before generated-key coercion became id-aware.
        // (Integer byte parity is pinned by collectionLoaderKeyFollowsIntegerIdType above.)
        String widget = Files.readString(generatedDir.resolve("appkey/WidgetRepositoryImpl.java"))
                .replace("\\\"", "\"").replaceAll("\\s+", " ");
        assertTrue(widget.contains("SQL_INSERT = \"INSERT INTO \"widget\" (\"name\") VALUES (:name)\""),
                "Long id keeps the identity column omitted:" + System.lineSeparator() + widget);
        assertTrue(widget.contains("GeneratedKeyHolder keys = new GeneratedKeyHolder(); jdbcClient.sql(SQL_INSERT)"
                        + ".param(\"name\", entity.getName()).update(keys); if (keys.getKey() != null) "
                        + "{ entity.setId(((Number) keys.getKey()).longValue()); }"),
                "Long id keeps the keyholder roundtrip byte-identically:" + System.lineSeparator() + widget);

        // Ruled narrowing: Short (with the other ex-allowlisted Number types) now takes the
        // application-assigned shape — this leg names the ruling so the suite cannot mistake
        // it for an accident.
        String segment = Files.readString(generatedDir.resolve("appkey/SegmentRepositoryImpl.java"))
                .replace("\\\"", "\"").replaceAll("\\s+", " ");
        assertTrue(segment.contains(
                        "jdbcClient.sql(SQL_INSERT).param(\"id\", entity.getId()).param(\"code\", entity.getCode()).update()"),
                "Short id takes the application-assigned shape:" + System.lineSeparator() + segment);
        assertTrue(!segment.contains("longValue") && !segment.contains("GeneratedKeyHolder"),
                "Short id must not do the keyholder roundtrip:" + System.lineSeparator() + segment);

        // NOTE-degrade on save for app-assigned ids; the compile continued (no ERRORs above).
        String notes = notesOf(diagnostics);
        assertTrue(notes.contains("id type java.lang.String requires application-assigned identifiers"),
                "String-id save must NOTE-degrade:" + System.lineSeparator() + notes);
        assertTrue(notes.contains("id type java.util.UUID requires application-assigned identifiers"),
                "UUID-id save must NOTE-degrade:" + System.lineSeparator() + notes);
        assertTrue(notes.contains("id type java.lang.Short requires application-assigned identifiers"),
                "Short-id save must NOTE-degrade:" + System.lineSeparator() + notes);
        assertTrue(!notes.contains("id type java.lang.Long requires application-assigned identifiers"),
                "Long-id save must stay silent:" + System.lineSeparator() + notes);
    }

    @Test
    void aggregateWritesCascadeEntityCollectionChildren() throws Exception {
        // The composition lifecycle is bound to the aggregate root. An EntityCollection whose target
        // clears the managed-target gate is now ALSO written: root insert runs the CHILD's own
        // generated-key insert per member with the inverse fk bound from the root id and backfills the
        // child id; root update replaces all (DELETE by inverse fk + re-insert); deleteById removes
        // children from the child's own table before the root row. This mirrors the element-collection
        // machinery (appendElementCollectionInsert / childDeleteConstant) on the child's OWN table.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/casc"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-casc"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-casc"));

        Files.writeString(sourceDir.resolve("Machine.java"), """
                package casc;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @AggregateRoot
                public class Machine {
                    @Identity Long id;
                    String name;
                    List<Part> parts = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public List<Part> getParts() { return parts; }
                    public void setParts(List<Part> parts) { this.parts = parts; }
                }
                """);
        Files.writeString(sourceDir.resolve("Part.java"), """
                package casc;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class Part {
                    @Identity Long id;
                    String label;
                    Machine owner;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                    public Machine getOwner() { return owner; }
                    public void setOwner(Machine owner) { this.owner = owner; }
                }
                """);
        Files.writeString(sourceDir.resolve("MachineRepository.java"), """
                package casc;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface MachineRepository extends CrudRepository<Machine, Long> { }
                """);
        Files.writeString(sourceDir.resolve("PartRepository.java"), """
                package casc;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface PartRepository extends CrudRepository<Part, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Machine.java", "Part.java", "MachineRepository.java", "PartRepository.java");

        String impl = Files.readString(generatedDir.resolve("casc/MachineRepositoryImpl.java"))
                .replace("\\\"", "\"").replaceAll("\\s+", " ");

        // Child INSERT constant: the child's OWN SqlBuilder insert (identity omitted, ALL child columns
        // incl. the inverse fk), byte-equal to what PartRepository would emit for the same descriptor.
        assertTrue(impl.contains("SQL_INSERT_PARTS = \"INSERT INTO \"part\" (\"label\", \"owner_id\") VALUES (:label, :owner)\""),
                "the cascade must reuse the child's own insert over its full column set:" + System.lineSeparator() + impl);
        // Child DELETE constant: child's own table keyed by the inverse fk (ElementCollection delete shape).
        assertTrue(impl.contains("SQL_DELETE_PARTS = \"DELETE FROM \"part\" WHERE \"owner_id\" = :owner_id\""),
                "the cascade needs a by-fk DELETE on the child table:" + System.lineSeparator() + impl);
        // Insert cascade: per-member child insert binding the fk from the ROOT id (the bare inverse is
        // never serialized), generated key backfilled onto the child.
        assertTrue(impl.contains("for (Part item : entity.getParts()) { if (item != null) { GeneratedKeyHolder childKeys = new GeneratedKeyHolder();"),
                "root insert must iterate the entity collection with a per-child key holder:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(SQL_INSERT_PARTS).param(\"label\", item.getLabel()).param(\"owner\", entity.getId()).update(childKeys)"),
                "the inverse-fk parameter must bind the ROOT id, other columns the child's own values:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("if (childKeys.getKey() != null) { item.setId(((Number) childKeys.getKey()).longValue()); }"),
                "the generated child id must be backfilled (flagship cross-check needs workable ids):"
                        + System.lineSeparator() + impl);
        // Update cascade: each child is UPSERTED under its own id rather than replace-all'd, so it
        // keeps the key it already had; the children the collection no longer names are deleted by key.
        assertTrue(impl.contains("SQL_UPSERT_PARTS = \"MERGE INTO \"part\" (\"id\", \"label\", \"owner_id\") KEY (\"id\") VALUES (:id, :label, :owner)\""),
                "the sync needs the child's own one-statement upsert:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(SQL_SELECT_IDS_PARTS).param(\"owner_id\", entity.getId()).query(Long.class).list()"),
                "the orphan pass must read the parent's existing child keys first:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("if (item.getId() != null) { jdbcClient.sql(SQL_UPSERT_PARTS).param(\"id\", item.getId()).param(\"label\", item.getLabel()).param(\"owner\", entity.getId()).update();"),
                "a child that has an id must be upserted under it, not re-inserted:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("for (Long orphan : keys_parts) { jdbcClient.sql(SQL_DELETE_ORPHAN_PARTS).param(\"owner_id\", entity.getId()).param(\"id\", orphan).update(); }"),
                "a child the collection no longer names must be deleted by key:"
                        + System.lineSeparator() + impl);
        // Replace-all is gone: a delete of the WHOLE child set no longer precedes the child loop, which
        // is what used to regenerate every child id on every save.
        assertFalse(impl.contains("SQL_DELETE_PARTS).param(\"owner_id\", entity.getId()).update(); for (Part item : entity.getParts())"),
                "the child set must no longer be deleted before it is written:" + System.lineSeparator() + impl);
        // deleteById cascade: children first (id value bound directly), then the root row.
        assertTrue(impl.contains("public void deleteById(Long id) { jdbcClient.sql(SQL_DELETE_PARTS).param(\"owner_id\", id).update(); jdbcClient.sql(SQL_DELETE).param(\"id\", id).update(); }"),
                "deleteById must remove cascaded children before the root:" + System.lineSeparator() + impl);
    }

    @Test
    void entityOwnerDoesNotCascadeEntityCollectionWrites() throws Exception {
        // The composition lifecycle is bound to the
        // root ONLY for @AggregateRoot owners. Inside an @Entity an EntityCollection is a collection of
        // ASSOCIATED objects — the READ hydration loader stays, but save/insert/update/deleteById must
        // emit NO child cascade: a replace-all on an association owner would silently delete rows the
        // owner does not own (the planned Team→@Entity conversion landmine).
        Path sourceDir = Files.createDirectories(workspace.resolve("src/cascnone"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-cascnone"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-cascnone"));

        Files.writeString(sourceDir.resolve("Device.java"), """
                package cascnone;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @Entity
                public class Device {
                    @Identity Long id;
                    String name;
                    List<Module> modules = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public List<Module> getModules() { return modules; }
                    public void setModules(List<Module> modules) { this.modules = modules; }
                }
                """);
        Files.writeString(sourceDir.resolve("Module.java"), """
                package cascnone;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class Module {
                    @Identity Long id;
                    String label;
                    Device device;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                    public Device getDevice() { return device; }
                    public void setDevice(Device device) { this.device = device; }
                }
                """);
        Files.writeString(sourceDir.resolve("DeviceRepository.java"), """
                package cascnone;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface DeviceRepository extends CrudRepository<Device, Long> { }
                """);
        Files.writeString(sourceDir.resolve("ModuleRepository.java"), """
                package cascnone;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface ModuleRepository extends CrudRepository<Module, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Device.java", "Module.java", "DeviceRepository.java", "ModuleRepository.java");

        String impl = Files.readString(generatedDir.resolve("cascnone/DeviceRepositoryImpl.java"))
                .replace("\\\"", "\"").replaceAll("\\s+", " ");

        // Read side UNCHANGED: the managed/mapped EntityCollection still gets its hydration loader.
        assertTrue(impl.contains("private void hydrateModules"),
                "an @Entity owner keeps its read hydration loader:" + System.lineSeparator() + impl);
        // Write side: NO child INSERT constant, NO child DELETE constant, no cascade loops anywhere.
        assertTrue(!impl.contains("SQL_INSERT_MODULES"),
                "an @Entity owner must not generate the child-insert cascade constant:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("SQL_DELETE_MODULES"),
                "an @Entity owner must not generate the child-delete cascade constant:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("INSERT INTO \"module\"") && !impl.contains("DELETE FROM \"module\""),
                "no child-table write statement may appear for an @Entity owner:"
                        + System.lineSeparator() + impl);
        // deleteById is the bare root delete (children-first cascade suppressed).
        assertTrue(impl.contains("public void deleteById(Long id) { jdbcClient.sql(SQL_DELETE).param(\"id\", id).update(); }"),
                "deleteById must delete ONLY the root row for an @Entity owner:" + System.lineSeparator() + impl);
    }

    @Test
    void writeCascadeDecoupledFromReadMapperGate() throws Exception {
        // The entity-collection write cascade is gated by WRITABILITY only (child id +
        // table metadata), mirroring the element-collection write path. A target failing the READ
        // managed/mapper gate keeps losing its loader (read NOTE) but still cascades on save/update/
        // deleteById; every genuine write skip (here: a non-generated-key-coercible id) records a
        // write-honest NOTE naming save/update/delete, so children are never dropped silently.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/cascgate"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-cascgate"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-cascgate"));

        Files.writeString(sourceDir.resolve("Hub.java"), """
                package cascgate;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @AggregateRoot
                public class Hub {
                    @Identity Long id;
                    String name;
                    List<Spoke> spokes = new ArrayList<>();
                    List<Tag> tags = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public List<Spoke> getSpokes() { return spokes; }
                    public void setSpokes(List<Spoke> spokes) { this.spokes = spokes; }
                    public List<Tag> getTags() { return tags; }
                    public void setTags(List<Tag> tags) { this.tags = tags; }
                }
                """);
        // Spoke fails the row-mapper gate (a List<String> scalar is not JDBC-safe) but is fully
        // writable: Long @Identity + resolved table — the write cascade must still be emitted.
        Files.writeString(sourceDir.resolve("Spoke.java"), """
                package cascgate;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @Entity
                public class Spoke {
                    @Identity Long id;
                    String label;
                    Hub hub;
                    List<String> notes = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                    public Hub getHub() { return hub; }
                    public void setHub(Hub hub) { this.hub = hub; }
                    public List<String> getNotes() { return notes; }
                    public void setNotes(List<String> notes) { this.notes = notes; }
                }
                """);
        // Tag is NOT cascadeable through the generated-key machinery (String id → not
        // generated-key coercible) and has no repository of its own. (A String-id
        // repository impl DOES compile — app-assigned insert shape — but that is irrelevant here:
        // the WRITE blocker degrades the CASCADE itself, which needs a Number key to backfill
        // child ids.) Its cascade must skip with a write-honest NOTE, and the read gate
        // independently leaves it unmapped.
        Files.writeString(sourceDir.resolve("Tag.java"), """
                package cascgate;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class Tag {
                    @Identity String id;
                    String label;
                    Hub hub;
                    public String getId() { return id; }
                    public void setId(String id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                    public Hub getHub() { return hub; }
                    public void setHub(Hub hub) { this.hub = hub; }
                }
                """);
        Files.writeString(sourceDir.resolve("HubRepository.java"), """
                package cascgate;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface HubRepository extends CrudRepository<Hub, Long> { }
                """);
        Files.writeString(sourceDir.resolve("SpokeRepository.java"), """
                package cascgate;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface SpokeRepository extends CrudRepository<Spoke, Long> { }
                """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(sourceDir,
                classesDir, generatedDir, "Hub.java", "Spoke.java", "Tag.java",
                "HubRepository.java", "SpokeRepository.java");

        String impl = Files.readString(generatedDir.resolve("cascgate/HubRepositoryImpl.java"))
                .replace("\\\"", "\"").replaceAll("\\s+", " ");

        // (1) READ gate: Spoke has no row mapper → no loader + the read-worded NOTE (unchanged gate).
        assertTrue(!impl.contains("private void hydrateSpokes"),
                "a read-gate-failed target must keep losing its hydration loader:"
                        + System.lineSeparator() + impl);
        String notes = notesOf(diagnostics);
        assertTrue(notes.contains("relationship 'spokes' on cascgate.Hub: target cascgate.Spoke has no generated row mapper"),
                "the read gate must still record its NOTE:" + System.lineSeparator() + notes);
        // (2) WRITE path DECOUPLED: despite the read skip, the plain-SQL cascade is emitted.
        assertTrue(impl.contains("SQL_INSERT_SPOKES"),
                "the write cascade must survive the READ gate skip (child id + table suffice):"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(SQL_DELETE_SPOKES).param(\"hub_id\", id).update(); jdbcClient.sql(SQL_DELETE).param(\"id\", id).update(); }"),
                "deleteById must cascade the read-gate-skipped child first (children-first):"
                        + System.lineSeparator() + impl);
        // (3) A genuine WRITE skip (non-coercible id) gets the write-honest NOTE, not silence.
        assertTrue(notes.contains("entity collection 'tags' on cascgate.Hub: target id type java.lang.String is not generated-key coercible — children will NOT be cascaded on save/update/delete"),
                "the write skip must carry a write-honest NOTE naming save/update/delete:"
                        + System.lineSeparator() + notes);
        assertTrue(!impl.contains("SQL_INSERT_TAGS") && !impl.contains("SQL_DELETE_TAGS"),
                "the blocked collection must not emit any cascade constant:"
                        + System.lineSeparator() + impl);
        // Read side for Tag stays gate-degraded (unmanaged target): no loader, read NOTE (unchanged).
        assertTrue(!impl.contains("hydrateTags"),
                "an unmanaged target must not get a hydration loader:" + System.lineSeparator() + impl);
        assertTrue(notes.contains("relationship 'tags' on cascgate.Hub: target cascgate.Tag has no generated row mapper — left unmapped on read"),
                "the read gate NOTE stays for the unmanaged tags collection:"
                        + System.lineSeparator() + notes);
    }

    @Test
    void anAggregateRootWritesItsToOneCompositionTargetBeforeItsOwnRow() throws Exception {
        // The root's row carries the target's foreign key, read from the live object, so a target with
        // no id yet must be written FIRST for the root's own bind to see the generated key. An
        // @Association (composition=false) is an external entity the root does not own and must not
        // write at all.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/comp"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-comp"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-comp"));

        Files.writeString(sourceDir.resolve("Author.java"), """
                package comp;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Author {
                    @Identity Long id;
                    String name;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        Files.writeString(sourceDir.resolve("Book.java"), """
                package comp;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Book {
                    @Identity Long id;
                    String title;
                    Author author;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public Author getAuthor() { return author; }
                    public void setAuthor(Author author) { this.author = author; }
                }
                """);
        Files.writeString(sourceDir.resolve("Books.java"), """
                package comp;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Books extends CrudRepository<Book, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Author.java", "Book.java", "Books.java");
        String impl = sqlFlat(generatedDir.resolve("comp/BooksImpl.java"));

        assertTrue(impl.contains("SQL_AUTHOR_UPSERT = \"MERGE INTO \"author\" (\"id\", \"name\") KEY (\"id\") VALUES (:id, :name)\""),
                "the composition target needs the root's upsert form:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("Author target_author = entity.getAuthor(); if (target_author != null) {"),
                "a null target must be skipped, not written:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("if (target_author.getId() == null) { GeneratedKeyHolder keys_author = new GeneratedKeyHolder();"),
                "a target with no id must be inserted with a key holder:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("target_author.setId(((Number) keys_author.getKey()).longValue());"),
                "the generated target id must be backfilled onto the object the root's fk binds:"
                        + System.lineSeparator() + impl);
        // The target's write precedes the root's, so the root binds an id that already exists.
        int targetWrite = impl.indexOf("target_author.getId() == null");
        int rootWrite = impl.indexOf("jdbcClient.sql(SQL_UPSERT)");
        assertTrue(targetWrite > 0 && rootWrite > targetWrite,
                "the composition target must be written BEFORE the root row:" + System.lineSeparator() + impl);
    }

    @Test
    void anAssociationTargetIsNeverWritten() throws Exception {
        // composition=false: the root stores the key and nothing more. Writing it would give the
        // aggregate authority over a row another aggregate owns.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/assoc"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-assoc"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-assoc"));

        Files.writeString(sourceDir.resolve("Vendor.java"), """
                package assoc;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Vendor {
                    @Identity Long id;
                    String name;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        Files.writeString(sourceDir.resolve("Invoice.java"), """
                package assoc;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Association;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Invoice {
                    @Identity Long id;
                    String code;
                    @Association Vendor vendor;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getCode() { return code; }
                    public void setCode(String code) { this.code = code; }
                    public Vendor getVendor() { return vendor; }
                    public void setVendor(Vendor vendor) { this.vendor = vendor; }
                }
                """);
        Files.writeString(sourceDir.resolve("Invoices.java"), """
                package assoc;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Invoices extends CrudRepository<Invoice, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Vendor.java", "Invoice.java", "Invoices.java");
        String impl = sqlFlat(generatedDir.resolve("assoc/InvoicesImpl.java"));

        assertFalse(impl.contains("SQL_VENDOR_UPSERT"),
                "an @Association target must not be written:" + System.lineSeparator() + impl);
        assertFalse(impl.contains("target_vendor"),
                "an @Association target must not get a write block:" + System.lineSeparator() + impl);
        // The positive counterpart the two negatives above need: "not written" means the target gets no
        // statement of its own, NOT that the root stops being written. Both negatives pass on an impl
        // that emits nothing at all, so pin the root's own upsert and the association's only footprint —
        // its fk id, bound inline on the root statement.
        assertTrue(impl.contains("SQL_UPSERT = \"MERGE INTO \"invoice\" (\"id\", \"code\", \"vendor_id\")"
                        + " KEY (\"id\") VALUES (:id, :code, :vendor)\""),
                "the root row must still be written:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"vendor\", entity.getVendor() == null ? null : entity.getVendor().getId())"),
                "the association's only write footprint is its fk id, inline on the root statement:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void batchWritesLoopTheSingleEntityBodyTheyAreNamedAfter() throws Exception {
        // The batch methods were pinned by SIGNATURE only, which a stub body satisfies: every one of
        // them writes the same columns, so a file-wide contains("SQL_UPDATE") proves nothing about
        // which method runs it. Scope each assertion to one method body instead — the real contract is
        // that each batch form repeats the single-entity body it is named after, and nothing else.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/batchwrite"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-batchwrite"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-batchwrite"));
        Files.writeString(sourceDir.resolve("Note.java"), """
                package batchwrite;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class Note {
                    @Identity Long id;
                    String text;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getText() { return text; }
                    public void setText(String text) { this.text = text; }
                }
                """);
        Files.writeString(sourceDir.resolve("Notes.java"), """
                package batchwrite;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Notes extends CrudRepository<Note, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Note.java", "Notes.java");
        String impl = sqlFlat(generatedDir.resolve("batchwrite/NotesImpl.java"));

        // saveAll keeps save's per-entity CHOICE: a null id inserts and reads the key back, a set id
        // upserts. A saveAll that unconditionally inserted would re-insert every already-persisted row.
        String saveAll = bodyOf(impl, "public <S extends Note> List<S> saveAll(List<S> entities) {");
        assertTrue(saveAll.contains("for (Note entity : entities) {"),
                "saveAll must iterate the given entities:" + System.lineSeparator() + saveAll);
        assertTrue(saveAll.contains("if (entity.getId() == null) {") && saveAll.contains("SQL_UPSERT"),
                "saveAll must keep the insert-or-upsert choice per entity, not pick one:"
                        + System.lineSeparator() + saveAll);
        assertTrue(saveAll.trim().endsWith("return entities; }"),
                "saveAll must hand back the same list it was given:" + System.lineSeparator() + saveAll);

        // insertAll has no choice to make: every entity is new, so every one takes the keyholder path.
        String insertAll = bodyOf(impl, "public <S extends Note> List<S> insertAll(List<S> entities) {");
        assertTrue(insertAll.contains("GeneratedKeyHolder") && insertAll.contains("SQL_INSERT"),
                "insertAll must insert every entity and read each generated key back:"
                        + System.lineSeparator() + insertAll);
        assertFalse(insertAll.contains("SQL_UPSERT"),
                "insertAll must never upsert — there is nothing to match on:"
                        + System.lineSeparator() + insertAll);

        // updateAll is the mirror: every entity already has an id, so there is no keyholder at all.
        String updateAll = bodyOf(impl, "public <S extends Note> List<S> updateAll(List<S> entities) {");
        assertTrue(updateAll.contains("SQL_UPDATE") && updateAll.contains(".param(\"id\", entity.getId())"),
                "updateAll must update by each entity's id:" + System.lineSeparator() + updateAll);
        assertFalse(updateAll.contains("GeneratedKeyHolder"),
                "updateAll must not ask for a generated key — the id is already set:"
                        + System.lineSeparator() + updateAll);

        String deleteAll = bodyOf(impl, "public void deleteAll(List<? extends Note> entities) {");
        assertTrue(deleteAll.contains("jdbcClient.sql(SQL_DELETE).param(\"id\", entity.getId()).update();"),
                "deleteAll must delete each entity by id:" + System.lineSeparator() + deleteAll);
    }

    // ------------------------------------------------------------------ upsert

    @Test
    void saveUpsertsInOneStatementWhenTheDialectHasAForm() throws Exception {
        // Jakarta Data's save is an upsert. H2 has MERGE ... KEY, so a save carrying an id issues ONE
        // statement and needs no presence test — the old update-then-insert (with its `== 0` branch)
        // is gone, and with it the second round trip.
        String impl = ProcessorHarness.saveUpsertImpl(workspace, "h2-upsert", null);
        assertTrue(impl.contains("SQL_UPSERT = \"MERGE INTO \"item\" (\"id\", \"name\") KEY (\"id\") VALUES (:id, :name)\""),
                "save must carry the dialect's one-statement upsert:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(SQL_UPSERT).param(\"id\", entity.getId()).param(\"name\", entity.getName()).update();"),
                "the upsert must bind the identifier too — it is the match key:"
                        + System.lineSeparator() + impl);
        assertFalse(impl.contains("== 0"),
                "with a one-statement upsert there is no presence test left:" + System.lineSeparator() + impl);
        // A null id has nothing to match on, so it still inserts and reads the generated key back.
        assertTrue(impl.contains("if (entity.getId() == null) {"),
                "a null id must still take the insert path:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("SQL_INSERT = \"INSERT INTO \"item\" (\"name\") VALUES (:name)\""),
                "the null-id insert must omit the identity column:" + System.lineSeparator() + impl);
    }

    @Test
    void saveOnPostgreSqlRendersOnConflictDoUpdate() throws Exception {
        // Same repository, different database: PostgreSQL has no MERGE, so the same save renders
        // INSERT ... ON CONFLICT. The column list and parameter names are unchanged, so the generated
        // .param(...) chain is identical — only the tail differs.
        String impl = ProcessorHarness.saveUpsertImpl(workspace, "pg-upsert", "postgresql");
        assertTrue(impl.contains("SQL_UPSERT = \"INSERT INTO \"item\" (\"id\", \"name\") VALUES (:id, :name)"
                        + " ON CONFLICT (\"id\") DO UPDATE SET \"name\" = EXCLUDED.\"name\""),
                "PostgreSQL must spell the upsert ON CONFLICT:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(SQL_UPSERT).param(\"id\", entity.getId()).param(\"name\", entity.getName()).update();"),
                "the parameter names must not move with the spelling:" + System.lineSeparator() + impl);
    }

    @Test
    void anEntityWhoseOnlyColumnIsItsIdentifierInsertsARowOfAllDefaults() throws Exception {
        // A generated-key insert drops the identity from the column list, which for this shape leaves
        // it EMPTY — and the constant used to read `INSERT INTO "stub" () VALUES ()`, which is not
        // SQL. The insert is still real: every column takes its default, the database generates the
        // identity, and the unchanged keyholder roundtrip reads it back. The shape is not exotic —
        // several fixtures in this suite are exactly this small — so it cannot be rejected at
        // compile time, which is why the rendering has to be right instead.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/id-only"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-id-only"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-id-only"));

        Files.writeString(sourceDir.resolve("Stub.java"), """
                package appidonly;
                import io.github.fludakit.data.annotation.Entity;
                @Entity
                public class Stub {
                    Long id;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                }
                """);
        Files.writeString(sourceDir.resolve("StubRepository.java"), """
                package appidonly;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface StubRepository extends CrudRepository<Stub, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Stub.java", "StubRepository.java");

        String impl = Files.readString(generatedDir.resolve("appidonly/StubRepositoryImpl.java"))
                .replace("\\\"", "\"").replaceAll("\\s+", " ");
        assertTrue(impl.contains("SQL_INSERT = \"INSERT INTO \"stub\" DEFAULT VALUES\""),
                "an entity with nothing to bind must insert a row of all defaults:"
                        + System.lineSeparator() + impl);
        assertFalse(impl.contains("() VALUES ()"),
                "the empty column list must not survive into any constant:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("GeneratedKeyHolder keys = new GeneratedKeyHolder(); "
                        + "jdbcClient.sql(SQL_INSERT).update(keys);"),
                "the keyholder roundtrip must still read the generated key back, binding nothing:"
                        + System.lineSeparator() + impl);
    }
}
