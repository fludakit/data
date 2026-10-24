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

        String impl = sqlFlat(generatedDir.resolve("elementinsert/Articles_.java"));
        // Parent fk FIRST — the opposite of the hydration SELECT, which drops it — then the leaves.
        assertTrue(impl.contains("EC_INSERT_TAGS = \"INSERT INTO \"tags\" (\"article_id\", \"label\","
                        + " \"weight\") VALUES (:parentId, :label, :weight)\""),
                "the element-collection INSERT must list the parent fk and every leaf column:"
                        + System.lineSeparator() + impl);
        // …and bind exactly those placeholders: the fk from the ROOT id, the leaves from the item.
        assertTrue(impl.contains("jdbcClient.sql(EC_INSERT_TAGS).param(\"parentId\", entity.getId())"
                        + ".param(\"label\", element.getLabel())"
                        + ".param(\"weight\", element.getWeight()).update()"),
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
                    @Query("SELECT id, title FROM books WHERE title = :title")
                    List<Book> search(String title);

                    @Query("SELECT id, title FROM books WHERE id = :id")
                    List<Book> search(Long id);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Book.java", "Shelf.java");

        String impl = sqlFlat(generatedDir.resolve("collidingconstants/Shelf_.java"));
        assertTrue(impl.contains("SEARCH_SQL = \"SELECT id, title FROM books WHERE title = :title\""),
                "the first statement must keep the bare base name:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("SEARCH_SQL_2 = \"SELECT id, title FROM books WHERE id = :id\""),
                "a second, different statement must be renamed rather than overwrite the first:"
                        + System.lineSeparator() + impl);
        // Both must still be USED, each by its own method: a rename that left one caller pointing at
        // the other's SQL would compile and return the wrong rows.
        assertTrue(statementOf(impl, "SEARCH_SQL_2)").contains(".param(\"id\", id)"),
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

        String impl = sqlFlat(generatedDir.resolve("ecnestedvo/Albums_.java"));
        assertTrue(impl.contains("INSERT INTO \"tracks\" (\"album_id\", \"sku\", \"point_lat\","
                        + " \"point_lon\")"),
                "a value object nested in an element-collection value object must flatten to one column"
                        + " per leaf, not one per property:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"point_lat\", element.getPoint() == null ? null"
                        + " : element.getPoint().getLat())"),
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

        String impl = sqlFlat(generatedDir.resolve("voca/Baskets_.java"));
        assertTrue(impl.contains("INSERT INTO \"lines\" (\"basket_id\", \"product_id\", \"quantity\")"
                        + " VALUES (:parentId, :product_id, :quantity)"),
                "child-table insert must keep the fk column:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"product_id\", element.getProduct() == null ? null : element.getProduct().getId())"),
                "@Association child column must bind the definition's id, not the definition object:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"quantity\", element.getQuantity())"),
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

        String impl = sqlFlat(generatedDir.resolve("hyd/Baskets_.java"));

        // A per-collection loader plus its SELECT constant and a delete constant for replace-all/cascade.
        assertTrue(impl.contains("HYDRATE_LINES"), "missing child SELECT constant:" + System.lineSeparator() + impl);
        // The emitted hydration SELECT is pinned as an exact constant string, not by shape: the
        // ASSOCIATION `product` column is excluded, so the list is parentFk + the flattened SCALAR
        // `quantity`. The loose `= :parentId` pattern matches the per-entity lookup.
        // The file's `\"` escapes are normalised to `"` by the read above, so the expected literal uses plain quotes.
        assertTrue(impl.contains("HYDRATE_LINES = \"SELECT * FROM "
                        + "\"lines\" WHERE \"basket_id\" = :parentId\""),
                "hydration SELECT constant value must be pinned:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("= :parentId"), "loader must use per-entity lookup:" + System.lineSeparator() + impl);
        // The generated loader takes a single entity parameter, not a collection.
        assertTrue(impl.contains("private void loadLines(Basket entity)"),
                "missing per-collection hydration loader:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("new LineRowMapper()"),
                "loader must pair rows through the generated VO row mapper:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("entity.setLines("), "loader must set children on the entity:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("EC_DELETE_LINES"), "missing child DELETE constant:" + System.lineSeparator() + impl);

        // The umbrella is emitted once and reached from every read shape.
        assertTrue(impl.contains("private void hydrate(Basket entity)"),
                "missing umbrella hydrate:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("loadLines(entity)"),
                "umbrella must call each loader:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("result.ifPresent(this::hydrate)"),
                "findById must hydrate through the umbrella:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("hydrateList(content)"),
                "paged read must hydrate the materialized content list:" + System.lineSeparator() + impl);

        // The hydrated @Find branches (LIST / STREAM / OPTIONAL / SINGLE) each wrap the umbrella.
        assertTrue(impl.contains("var result = jdbcClient .sql(FIND_BY_NAME_SQL)"
                        + " .param(\"name\", name) .query(new BasketRowMapper()) .list(); hydrateList(result); return result;"),
                "hydrated LIST @Find must materialize, hydrateList, then return:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("var result = jdbcClient .sql(STREAM_BY_NAME_SQL)"
                        + " .param(\"name\", name) .query(new BasketRowMapper()) .list(); hydrateList(result); return result.stream();"),
                "hydrated STREAM @Find must materialize, hydrateList, then stream:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("var result = jdbcClient .sql(OPTIONAL_BY_NAME_SQL)"
                        + " .param(\"name\", name) .query(new BasketRowMapper()) .optional(); "
                        + "result.ifPresent(this::hydrate); return result;"),
                "hydrated OPTIONAL @Find must hydrate the present value:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("var result = jdbcClient .sql(SINGLE_BY_NAME_SQL)"
                        + " .param(\"name\", name) .query(new BasketRowMapper()) .single(); "
                        + "hydrate(result); return result;"),
                "hydrated SINGLE @Find must hydrate the single value:"
                        + System.lineSeparator() + impl);

        // Replace-all update: children deleted by parent fk (child param stays COLUMN-named), then re-inserted.
        assertTrue(impl.contains(".param(\"parentId\", entity.getId())"),
                "update must delete children by the parent fk before re-inserting:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("DELETE FROM \"lines\" WHERE \"basket_id\" = :parentId"),
                "delete-cascade constant:" + System.lineSeparator() + impl);

        // The generated VO row mapper reads the primitive leaf by column label and hydrates the
        // @Association child as an id-only partial (id from the join projection, or the bare fk
        // column fallback) instead of leaving it null or pulling the whole definition.
        String voMapper = Files.readString(generatedDir.resolve("hyd/LineRowMapper.java"));
        assertTrue(voMapper.contains("line.setQuantity(rs.getInt(\"quantity\"))"),
                "primitive leaf must read by column label:" + System.lineSeparator() + voMapper);
        assertTrue(voMapper.contains("product.setId(product_joined_id)")
                        && voMapper.contains("product.setId(product_fk)"),
                "@Association child must be hydrated as an id-only partial from both projections:"
                        + System.lineSeparator() + voMapper);
        assertTrue(voMapper.contains("line.setProduct(product)"),
                "@Association child must be set on the VO:" + System.lineSeparator() + voMapper);

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

        String impl = sqlFlat(generatedDir.resolve("casc/Baskets_.java"));

        // Save still inserts child rows for the gate-failed collection (unchanged write side).
        assertTrue(impl.contains("INSERT INTO \"lines\""),
                "child rows must be inserted for every collection:" + System.lineSeparator() + impl);

        // The DELETE cascade (replace-all update + deleteById) is emitted even for the gate-failed
        // collection: the cascade keeps the child rows from orphaning.
        assertTrue(impl.contains("EC_DELETE_LINES"),
                "gate-failed collection must still get its child DELETE constant:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("DELETE FROM \"lines\" WHERE \"basket_id\" = :parentId"),
                "gate-failed collection must still get the child DELETE SQL:" + System.lineSeparator() + impl);

        // With the LoadStrategy refactor, hydration is always generated (no conditional logic).
        // The gate-failed collection now gets hydration methods and constants.
        assertTrue(impl.contains("HYDRATE_LINES"),
                "hydration SELECT is now always emitted:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("loadLines"),
                "loader is now always emitted:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("hydrate"),
                "umbrella hydrate is now always emitted:" + System.lineSeparator() + impl);
        assertTrue(Files.exists(generatedDir.resolve("casc/LineRowMapper.java")),
                "gate-failed VO now gets a row mapper since hydration is always generated");

        // Hydration is always generated now, so the gate-skip NOTE is gone.
        String notes = notesOf(diagnostics);
        assertTrue(!notes.contains("not reflection-free mappable"),
                "the gate-skip NOTE must no longer be recorded:" + System.lineSeparator() + notes);
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

        String impl = Files.readString(generatedDir.resolve("ihd/Baskets_.java"))
                .replace("\\\"", "\"")
                .replaceAll("\\s+", " ");

        // The FK read uses the per-entity loader pattern with parentId parameter.
        assertTrue(impl.contains("HYDRATE_LINES"),
                "loader must have hydration SELECT constant:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("loadLines(Basket entity)"),
                "loader must use per-entity pattern:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"parentId\", entity.getId())"),
                "loader must bind parentId from entity id:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("entity.setLines(items)"),
                "loader must set children on entity:" + System.lineSeparator() + impl);
        // And the loader must NOT retain the hardcoded Long key (the paged COUNT's Long.class total
        // is unrelated to the collection key type).
        String loader = bodyOf(impl, "private void loadLines(Basket entity) {");
        assertTrue(!loader.contains("Long.class"),
                "loader must not hardcode Long:" + System.lineSeparator() + impl);

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

        String ticket = sqlFlat(generatedDir.resolve("appkey/TicketRepository_.java"));
        // No-keyholder shape: the identity column is part of the INSERT and bound from the entity.
        assertTrue(ticket.contains("INSERT = \"INSERT INTO \"tickets\" (\"id\", \"label\") VALUES (:id, :label)\""),
                "the app-assigned id must be an INSERT column:" + System.lineSeparator() + ticket);
        assertTrue(ticket.contains(
                        "jdbcClient.sql(INSERT).param(\"id\", entity.getId()).param(\"label\", entity.getLabel()).update()"),
                "the pre-assigned id parameter must be bound and the update must skip the keyholder:"
                        + System.lineSeparator() + ticket);
        assertTrue(!ticket.contains("longValue"),
                "String id must not use the Number coercion:" + System.lineSeparator() + ticket);
        assertTrue(!ticket.contains("GeneratedKeyHolder") && !ticket.contains("keys.getKey()"),
                "String id must not do the keyholder roundtrip:" + System.lineSeparator() + ticket);

        String badge = sqlFlat(generatedDir.resolve("appkey/BadgeRepository_.java"));
        assertTrue(badge.contains("INSERT = \"INSERT INTO \"badges\" (\"serial\") VALUES (:serial)\""),
                "UUID id omits the identity column:" + System.lineSeparator() + badge);
        assertTrue(badge.contains("entity.setId((UUID) keys.getKey())"),
                "UUID id is cast directly from the generated key:" + System.lineSeparator() + badge);

        // Long-id byte parity: the keyholder emission is textually identical to the shape it had
        // before generated-key coercion became id-aware.
        // (Integer byte parity is pinned by collectionLoaderKeyFollowsIntegerIdType above.)
        String widget = sqlFlat(generatedDir.resolve("appkey/WidgetRepository_.java"));
        assertTrue(widget.contains("INSERT = \"INSERT INTO \"widgets\" (\"name\") VALUES (:name)\""),
                "Long id keeps the identity column omitted:" + System.lineSeparator() + widget);
        assertTrue(widget.contains("GeneratedKeyHolder keys = new GeneratedKeyHolder(); jdbcClient.sql(INSERT)"
                        + ".param(\"name\", entity.getName()).update(keys); if (keys.getKey() != null) "
                        + "{ entity.setId(((Number) keys.getKey()).longValue()); }"),
                "Long id keeps the keyholder roundtrip byte-identically:" + System.lineSeparator() + widget);

        // Short is a numeric generated key, read back through Number's accessor (like Long/Integer).
        String segment = sqlFlat(generatedDir.resolve("appkey/SegmentRepository_.java"));
        assertTrue(segment.contains("INSERT = \"INSERT INTO \"segments\" (\"code\") VALUES (:code)\""),
                "Short id omits the identity column:" + System.lineSeparator() + segment);
        assertTrue(segment.contains("entity.setId(((Number) keys.getKey()).shortValue())"),
                "Short id reads through Number.shortValue():" + System.lineSeparator() + segment);

        // NOTE-degrade on save for the one application-assigned id type (String); the coercible
        // types (Long, Integer, Short, BigInteger, BigDecimal, UUID) stay silent.
        String notes = notesOf(diagnostics);
        assertTrue(!notes.contains("id type java.lang.String requires application-assigned identifiers"),
                "String-id save must no longer NOTE-degrade:" + System.lineSeparator() + notes);
        assertTrue(!notes.contains("id type java.util.UUID requires application-assigned identifiers"),
                "UUID-id save must stay silent:" + System.lineSeparator() + notes);
        assertTrue(!notes.contains("id type java.lang.Short requires application-assigned identifiers"),
                "Short-id save must stay silent:" + System.lineSeparator() + notes);
        assertTrue(!notes.contains("id type java.lang.Long requires application-assigned identifiers"),
                "Long-id save must stay silent:" + System.lineSeparator() + notes);
    }

    @Test
    void aggregateWritesCascadeEntityCollectionChildren() throws Exception {
        // The composition lifecycle is bound to the aggregate root. An EntityCollection whose definition
        // clears the managed-definition gate is now ALSO written: root insert runs the CHILD's own
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

        String impl = sqlFlat(generatedDir.resolve("casc/MachineRepository_.java"));

        // Child INSERT constant: the child's OWN SqlBuilder insert (identity omitted, ALL child columns
        // incl. the inverse fk), byte-equal to what PartRepository would emit for the same descriptor.
        assertTrue(impl.contains("EC_INSERT_PARTS = \"INSERT INTO \"parts\" (\"label\", \"owner_id\") VALUES (:label, :owner)\""),
                "the cascade must reuse the child's own insert over its full column set:" + System.lineSeparator() + impl);
        // Child UPDATE constant: the child's own one-statement update.
        assertTrue(impl.contains("EC_UPDATE_PARTS = \"UPDATE \"parts\" SET \"label\" = :label, \"owner_id\" = :owner WHERE \"id\" = :id\""),
                "the sync needs the child's own update:" + System.lineSeparator() + impl);
        // Orphan DELETE constant: keyed by BOTH child id and the inverse fk.
        assertTrue(impl.contains("EC_DELETE_PARTS = \"DELETE FROM \"parts\" WHERE \"id\" = :childId AND \"machine_id\" = :parentId\""),
                "the orphan pass must delete by child id and parent fk:" + System.lineSeparator() + impl);
        // Existing-ids SELECT constant: reads the parent's current child keys.
        assertTrue(impl.contains("EC_IDS_PARTS = \"SELECT \"id\" FROM \"parts\" WHERE \"machine_id\" = :parentId\""),
                "the orphan pass must read the parent's existing child keys first:" + System.lineSeparator() + impl);
        // deleteById cascade constant: the whole child set by fk.
        assertTrue(impl.contains("EC_COLL_DELETE_PARTS = \"DELETE FROM \"parts\" WHERE \"machine_id\" = :parentId\""),
                "deleteById needs a by-fk DELETE on the child table:" + System.lineSeparator() + impl);
        // Insert cascade: per-member child insert binding the fk from the ROOT id and backfilling the key.
        assertTrue(impl.contains("Set<Long> existingIds_parts = new HashSet<>(jdbcClient .sql(EC_IDS_PARTS) .param(\"parentId\", entity.getId()) .query(Long.class) .list())"),
                "the diff-sync must load existing child ids keyed by the parent fk:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(EC_INSERT_PARTS).param(\"label\", child_parts.getLabel()).param(\"owner\", entity.getId()).update(ecKeys_parts)"),
                "the inverse-fk parameter must bind the ROOT id, other columns the child's own values:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("child_parts.setId(((Number) ecKeys_parts.getKey()).longValue())"),
                "the generated child id must be backfilled:" + System.lineSeparator() + impl);
        // Update cascade: a child that already has an id is updated under it.
        assertTrue(impl.contains("jdbcClient.sql(EC_UPDATE_PARTS).param(\"id\", child_parts.getId()).param(\"label\", child_parts.getLabel()).param(\"owner\", entity.getId()).update()"),
                "a child that has an id must be updated under it, not re-inserted:"
                        + System.lineSeparator() + impl);
        // Orphan pass: children the collection no longer names are deleted by key.
        assertTrue(impl.contains("jdbcClient.sql(EC_DELETE_PARTS).param(\"childId\", orphanId).param(\"parentId\", entity.getId()).update()"),
                "a child the collection no longer names must be deleted by key:"
                        + System.lineSeparator() + impl);
        // Replace-all is gone: a delete of the WHOLE child set no longer precedes the child loop.
        assertFalse(impl.contains("EC_COLL_DELETE_PARTS).param(\"parentId\", entity.getId()).update(); for (var child_parts"),
                "the child set must no longer be deleted before it is written:" + System.lineSeparator() + impl);
        // deleteById cascade: children first (fk value bound directly), then the root row.
        String deleteByIdCascade = bodyOf(impl, "public void deleteById(Long id) {");
        assertTrue(deleteByIdCascade.contains("jdbcClient.sql(EC_COLL_DELETE_PARTS).param(\"parentId\", id).update()"),
                "deleteById must remove cascaded children before the root:" + System.lineSeparator() + impl);
        assertTrue(deleteByIdCascade.contains("jdbcClient .sql(DELETE_BY_ID_SQL) .param(\"id\", id) .update()"),
                "deleteById must then delete the root row:" + System.lineSeparator() + impl);
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

        String impl = sqlFlat(generatedDir.resolve("cascnone/DeviceRepository_.java"));

        // Read side UNCHANGED: the managed/mapped EntityCollection still gets its hydration loader.
        assertTrue(impl.contains("HYDRATE_MODULES") && impl.contains("loadModules(Device entity)"),
                "an @Entity owner keeps its read hydration loader:" + System.lineSeparator() + impl);
        // Write side: NO child INSERT constant, NO child DELETE constant, no cascade loops anywhere.
        assertTrue(!impl.contains("INSERT_MODULES"),
                "an @Entity owner must not generate the child-insert cascade constant:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("DELETE_MODULES"),
                "an @Entity owner must not generate the child-delete cascade constant:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("INSERT INTO \"modules\"") && !impl.contains("DELETE FROM \"modules\""),
                "no child-table write statement may appear for an @Entity owner:"
                        + System.lineSeparator() + impl);
        // deleteById is the bare root delete (children-first cascade suppressed).
        String deleteRootOnly = bodyOf(impl, "public void deleteById(Long id) {");
        assertTrue(deleteRootOnly.contains("jdbcClient .sql(DELETE_BY_ID_SQL) .param(\"id\", id) .update()")
                        && !deleteRootOnly.contains("DELETE_MODULES"),
                "deleteById must delete ONLY the root row for an @Entity owner:" + System.lineSeparator() + impl);
    }

    @Test
    void entityCollectionsWithGeneratedAndAppAssignedIdsBothHydrateAndCascade() throws Exception {
        // Both the generated-key (Long id) and app-assigned (String id) entity collections are fully
        // managed by the NavigationPlan/MutationPlan architecture: each gets its hydration loader AND
        // its diff-sync write cascade. The id type only changes the sync shape — a Number key inserts
        // children with a GeneratedKeyHolder and backfills ids, an app-assigned key always updates by
        // the child's existing id — while the children-first delete cascade and per-entity loaders are
        // identical for both.
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
        // Spoke carries a generated Long key plus a non-columnar List<String> property. The scalar list
        // is simply unmapped, not a mapping blocker: Spoke stays fully managed, so it hydrates AND
        // cascades like any other generated-key entity collection.
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
        // Tag has an app-assigned String id: no generated-key roundtrip is possible, so its diff-sync
        // update always binds the child's own id (no INSERT/backfill branch). It is still fully managed
        // — hydrated on read and cascade-written on save/update/delete with a String-keyed id set.
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

        String impl = sqlFlat(generatedDir.resolve("cascgate/HubRepository_.java"));

        // No read-gate or write-skip NOTE: both children are fully managed now.
        String notes = notesOf(diagnostics);
        assertTrue(!notes.contains("has no generated row mapper") && !notes.contains("not generated-key coercible"),
                "no read-gate or write-skip NOTE is expected when both children are managed:"
                        + System.lineSeparator() + notes);

        // Read side: both collections get their per-entity hydration loader.
        assertTrue(impl.contains("HYDRATE_SPOKES") && impl.contains("loadSpokes(Hub entity)"),
                "the generated-key collection must hydrate via its loader:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("HYDRATE_TAGS") && impl.contains("loadTags(Hub entity)"),
                "the app-assigned collection must hydrate via its loader:" + System.lineSeparator() + impl);

        // Write side (generated-key Spoke): insert-with-keyholder, update, and child-keyed delete.
        assertTrue(impl.contains("EC_INSERT_SPOKES = \"INSERT INTO \"spokes\" (\"label\", \"hub_id\") VALUES (:label, :hub)\""),
                "generated-key children must insert without binding the id:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("GeneratedKeyHolder ecKeys_spokes")
                        && impl.contains("child_spokes.setId(((Number) ecKeys_spokes.getKey()).longValue())"),
                "generated child keys must be backfilled onto the child object:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("Set<Long> existingIds_spokes")
                        && impl.contains(".query(Long.class)")
                        && impl.contains("for (Long orphanId : existingIds_spokes)"),
                "the generated-key id set and orphan loop must follow the Long id type:"
                        + System.lineSeparator() + impl);

        // Write side (app-assigned Tag): the id column is bound on INSERT, and the sync is always-update
        // by the child's own id — there is no keyholder branch.
        assertTrue(impl.contains("EC_INSERT_TAGS = \"INSERT INTO \"tags\" (\"id\", \"label\", \"hub_id\") VALUES (:id, :label, :hub)\""),
                "app-assigned children must bind the id on INSERT:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(EC_UPDATE_TAGS).param(\"id\", child_tags.getId()).param(\"label\", child_tags.getLabel()).param(\"hub\", entity.getId()).update()"),
                "app-assigned children must always update by their own id (no keyholder branch):"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("GeneratedKeyHolder ecKeys_tags"),
                "app-assigned children must not generate a keyholder branch:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("Set<String> existingIds_tags")
                        && impl.contains(".query(String.class)")
                        && impl.contains("for (String orphanId : existingIds_tags)"),
                "the app-assigned id set and orphan loop must follow the String id type:"
                        + System.lineSeparator() + impl);

        // deleteById cascades children first, in collection order, then the root row.
        String deleteByIdCascade = bodyOf(impl, "public void deleteById(Long id) {");
        assertTrue(deleteByIdCascade.contains("jdbcClient.sql(EC_COLL_DELETE_SPOKES).param(\"parentId\", id).update()")
                        && deleteByIdCascade.contains("jdbcClient.sql(EC_COLL_DELETE_TAGS).param(\"parentId\", id).update()"),
                "deleteById must cascade every collection before the root row:" + System.lineSeparator() + impl);
        assertTrue(deleteByIdCascade.indexOf("EC_COLL_DELETE_SPOKES") < deleteByIdCascade.indexOf("EC_COLL_DELETE_TAGS")
                        && deleteByIdCascade.indexOf("EC_COLL_DELETE_TAGS") < deleteByIdCascade.indexOf("DELETE_BY_ID_SQL"),
                "deleteById must cascade spokes, then tags, then the root row:" + System.lineSeparator() + impl);
    }

    @Test
    void anAggregateRootWritesItsToOneCompositionTargetBeforeItsOwnRow() throws Exception {
        // The root's row carries the definition's foreign key, read from the live object, so a definition with
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
        String impl = sqlFlat(generatedDir.resolve("comp/Books_.java"));

        assertTrue(impl.contains("CASCADE_INSERT_AUTHOR = \"INSERT INTO \"authors\" (\"name\") VALUES (:name)\""),
                "the composition definition needs the child's own insert:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("CASCADE_UPDATE_AUTHOR = \"UPDATE \"authors\" SET \"name\" = :name WHERE \"id\" = :id\""),
                "the composition definition needs the child's own update:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("if (entity.author != null) {"),
                "a null definition must be skipped, not written:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("if (entity.author.getId() == null) { GeneratedKeyHolder childKeys_author = new GeneratedKeyHolder();"),
                "a definition with no id must be inserted with a key holder:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("entity.author.setId(((Number) childKeys_author.getKey()).longValue())"),
                "the generated definition id must be backfilled onto the object the root's fk binds:"
                        + System.lineSeparator() + impl);
        // The definition's write precedes the root's, so the root binds an id that already exists.
        int targetWrite = impl.indexOf("entity.author.getId() == null");
        int rootWrite = impl.indexOf("jdbcClient.sql(UPSERT)");
        assertTrue(targetWrite > 0 && rootWrite > targetWrite,
                "the composition definition must be written BEFORE the root row:" + System.lineSeparator() + impl);
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
        String impl = sqlFlat(generatedDir.resolve("assoc/Invoices_.java"));

        assertFalse(impl.contains("SQL_VENDOR_UPSERT"),
                "an @Association definition must not be written:" + System.lineSeparator() + impl);
        assertFalse(impl.contains("target_vendor"),
                "an @Association definition must not get a write block:" + System.lineSeparator() + impl);
        // The positive counterpart the two negatives above need: "not written" means the definition gets no
        // statement of its own, NOT that the root stops being written. Both negatives pass on an impl
        // that emits nothing at all, so pin the root's own upsert and the association's only footprint —
        // its fk id, bound inline on the root statement.
        assertTrue(impl.contains("UPSERT = \"MERGE INTO \"invoices\" (\"id\", \"code\", \"vendor_id\")"
                        + " KEY (\"id\") VALUES (:id, :code, :vendor)\""),
                "the root row must still be written:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"vendor\", entity.getVendor() == null ? null : entity.getVendor().getId())"),
                "the association's only write footprint is its fk id, inline on the root statement:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void batchWritesLoopTheSingleEntityBodyTheyAreNamedAfter() throws Exception {
        // The batch methods were pinned by SIGNATURE only, which a stub body satisfies: every one of
        // them writes the same columns, so a file-wide contains("UPDATE") proves nothing about
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
        String impl = sqlFlat(generatedDir.resolve("batchwrite/Notes_.java"));

        // Each batch form delegates to the single-entity method it is named after.
        String saveAll = bodyOf(impl, "public <S extends Note> List<S> saveAll(List<S> entities) {");
        assertTrue(saveAll.contains("for (S entity : entities) { save(entity); result.add(entity); }"),
                "saveAll must delegate each entity to save():" + System.lineSeparator() + saveAll);
        assertTrue(saveAll.contains("return result;"),
                "saveAll must return the collected list:" + System.lineSeparator() + saveAll);

        String insertAll = bodyOf(impl, "public <S extends Note> List<S> insertAll(List<S> entities) {");
        assertTrue(insertAll.contains("for (S entity : entities) { insert(entity); result.add(entity); }"),
                "insertAll must delegate each entity to insert():" + System.lineSeparator() + insertAll);
        assertFalse(insertAll.contains("GeneratedKeyHolder"),
                "insertAll must not duplicate the keyholder logic — insert() owns it:"
                        + System.lineSeparator() + insertAll);

        String updateAll = bodyOf(impl, "public <S extends Note> List<S> updateAll(List<S> entities) {");
        assertTrue(updateAll.contains("for (S entity : entities) { update(entity); result.add(entity); }"),
                "updateAll must delegate each entity to update():" + System.lineSeparator() + updateAll);

        String deleteAll = bodyOf(impl, "public void deleteAll(List<? extends Note> entities) {");
        assertTrue(deleteAll.contains("for (Note entity : entities) { deleteById(entity.getId()); }"),
                "deleteAll must delegate each entity to deleteById():" + System.lineSeparator() + deleteAll);
    }

    // ------------------------------------------------------------------ upsert

    @Test
    void saveUpsertsInOneStatementWhenTheDialectHasAForm() throws Exception {
        // Jakarta Data's save is an upsert. H2 has MERGE ... KEY, so a save carrying an id issues ONE
        // statement and needs no presence test — the old update-then-insert (with its `== 0` branch)
        // is gone, and with it the second round trip.
        String impl = ProcessorHarness.saveUpsertImpl(workspace, "h2-upsert", null);
        assertTrue(impl.contains("UPSERT = \"MERGE INTO \"items\" (\"id\", \"name\") KEY (\"id\") VALUES (:id, :name)\""),
                "save must carry the dialect's one-statement upsert:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(UPSERT).param(\"id\", entity.getId()).param(\"name\", entity.getName()).update();"),
                "the upsert must bind the identifier too — it is the match key:"
                        + System.lineSeparator() + impl);
        assertFalse(impl.contains("== 0"),
                "with a one-statement upsert there is no presence test left:" + System.lineSeparator() + impl);
        // A null id has nothing to match on, so it still inserts and reads the generated key back.
        assertTrue(impl.contains("if (entity.getId() == null) {"),
                "a null id must still take the insert path:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("INSERT = \"INSERT INTO \"items\" (\"name\") VALUES (:name)\""),
                "the null-id insert must omit the identity column:" + System.lineSeparator() + impl);
    }

    @Test
    void saveOnPostgreSqlRendersOnConflictDoUpdate() throws Exception {
        // Same repository, different database: PostgreSQL has no MERGE, so the same save renders
        // INSERT ... ON CONFLICT. The column list and parameter names are unchanged, so the generated
        // .param(...) chain is identical — only the tail differs.
        String impl = ProcessorHarness.saveUpsertImpl(workspace, "pg-upsert", "postgresql");
        assertTrue(impl.contains("UPSERT = \"INSERT INTO \"items\" (\"id\", \"name\") VALUES (:id, :name)"
                        + " ON CONFLICT (\"id\") DO UPDATE SET \"name\" = EXCLUDED.\"name\""),
                "PostgreSQL must spell the upsert ON CONFLICT:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(UPSERT).param(\"id\", entity.getId()).param(\"name\", entity.getName()).update();"),
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

        String impl = sqlFlat(generatedDir.resolve("appidonly/StubRepository_.java"));
        assertTrue(impl.contains("INSERT = \"INSERT INTO \"stubs\" DEFAULT VALUES\""),
                "an entity with nothing to bind must insert a row of all defaults:"
                        + System.lineSeparator() + impl);
        assertFalse(impl.contains("() VALUES ()"),
                "the empty column list must not survive into any constant:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("GeneratedKeyHolder keys = new GeneratedKeyHolder(); "
                        + "jdbcClient.sql(INSERT).update(keys);"),
                "the keyholder roundtrip must still read the generated key back, binding nothing:"
                        + System.lineSeparator() + impl);
    }
}
