package io.github.fludakit.data.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryProcessorTest {

    @TempDir
    Path workspace;

    @Test
    void generatesImplementationForRepositoryInterface() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/demo"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated"));

        Files.writeString(sourceDir.resolve("Book.java"), """
                package demo;

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
                package demo;

                import jakarta.data.repository.By;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Find;
                import jakarta.data.repository.Repository;
                import java.util.List;

                @Repository
                public interface Shelf extends CrudRepository<Book, Long> {

                    @Find
                    List<Book> findByTitle(@By("title") String title);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Book.java", "Shelf.java");

        Path implementation = generatedDir.resolve("demo/ShelfImpl.java");
        assertTrue(Files.exists(implementation), "Expected generated " + implementation);

        String content = Files.readString(implementation);
        assertTrue(content.contains("implements Shelf"), "missing superinterface:" + System.lineSeparator() + content);
        assertTrue(content.contains("JdbcClient"), "missing JdbcClient injection:" + System.lineSeparator() + content);
        assertTrue(content.contains("SQL_FIND_BY_TITLE"), "missing static SQL constant:" + System.lineSeparator() + content);
        assertTrue(content.contains("jdbcClient.sql(SQL_FIND_BY_TITLE)"),
                "missing direct JdbcClient call:" + System.lineSeparator() + content);
        assertTrue(content.contains("findByTitle"), "missing generated method:" + System.lineSeparator() + content);
    }

    @Test
    void generatesRowMapperAndMetadataForSafeEntity() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/demo"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated"));

        Files.writeString(sourceDir.resolve("Book.java"), """
                package demo;

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
                package demo;

                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;

                @Repository
                public interface Shelf extends CrudRepository<Book, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Book.java", "Shelf.java");

        Path metadata = generatedDir.resolve("demo/BookMetadata.java");
        assertTrue(Files.exists(metadata), "Expected generated " + metadata);
        String md = Files.readString(metadata);
        assertTrue(md.contains("public static final String TABLE_NAME = \"book\";"), md);
        assertTrue(md.contains("public static final String ID = \"id\";"), md);
        assertTrue(md.contains("public static final String TITLE = \"title\";"), md);

        Path mapper = generatedDir.resolve("demo/BookRowMapper.java");
        assertTrue(Files.exists(mapper), "Expected generated " + mapper);
        String mp = Files.readString(mapper);
        assertTrue(mp.contains("implements RowMapper<Book>"), mp);
        assertTrue(mp.contains("import io.github.fludakit.jdbc.RowMapper;"), mp);
        assertTrue(mp.contains("rs.getObject(BookMetadata.ID, Long.class)"), mp);
        assertTrue(mp.contains("rs.getObject(BookMetadata.TITLE, String.class)"), mp);
        assertTrue(mp.contains("entity.setTitle("), mp);
        assertTrue(mp.contains("return entity;"), mp);
    }

    @Test
    void skipsRowMapperWhenFieldTypeIsNotJdbcSafe() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/demo2"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes2"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated2"));

        Files.writeString(sourceDir.resolve("Status.java"), """
                package demo2;
                public enum Status { OPEN, CLOSED }
                """);
        Files.writeString(sourceDir.resolve("Ticket.java"), """
                package demo2;
                public class Ticket {
                    public Long id;
                    public Status status;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Status getStatus() { return status; }
                    public void setStatus(Status status) { this.status = status; }
                }
                """);
        Files.writeString(sourceDir.resolve("Tickets.java"), """
                package demo2;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Tickets extends CrudRepository<Ticket, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Status.java", "Ticket.java", "Tickets.java");

        assertTrue(Files.exists(generatedDir.resolve("demo2/TicketsImpl.java")),
                "repository impl must still be generated");
        assertTrue(Files.notExists(generatedDir.resolve("demo2/TicketRowMapper.java")),
                "no mapper for an entity with a non-JDBC-safe field type");
        assertTrue(Files.notExists(generatedDir.resolve("demo2/TicketMetadata.java")),
                "no metadata when the mapper is skipped");

        // The central fallback promise: unsafe entities keep the reflective class-based read path.
        String impl = Files.readString(generatedDir.resolve("demo2/TicketsImpl.java"));
        assertTrue(impl.contains(".query(Ticket.class)"),
                "convention reads must fall back to the reflective class path:" + System.lineSeparator() + impl);
    }

    @Test
    void generatesRowMapperWhenValueObjectCollectionHasPrimitiveField() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/demo3"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes3"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated3"));

        Files.writeString(sourceDir.resolve("Line.java"), """
                package demo3;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Line {
                    public int quantity;
                    public String note;
                    public Line() { }
                    public int getQuantity() { return quantity; }
                    public void setQuantity(int quantity) { this.quantity = quantity; }
                    public String getNote() { return note; }
                    public void setNote(String note) { this.note = note; }
                }
                """);
        Files.writeString(sourceDir.resolve("Basket.java"), """
                package demo3;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                public class Basket {
                    @Identity public Long id;
                    public List<Line> lines = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public List<Line> getLines() { return lines; }
                    public void setLines(List<Line> lines) { this.lines = lines; }
                }
                """);
        Files.writeString(sourceDir.resolve("Baskets.java"), """
                package demo3;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Baskets extends CrudRepository<Basket, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Line.java", "Basket.java", "Baskets.java");

        assertTrue(Files.exists(generatedDir.resolve("demo3/BasketsImpl.java")),
                "repository impl must still be generated");
        Path mapper = generatedDir.resolve("demo3/BasketRowMapper.java");
        assertTrue(Files.exists(mapper),
                "a value-object collection with a primitive field must not abort mapper generation");
        String mp = Files.readString(mapper);
        assertTrue(mp.contains("entity.setId("), mp);
        assertTrue(mp.contains("return entity;"), mp);
        assertTrue(Files.exists(generatedDir.resolve("demo3/BasketMetadata.java")),
                "metadata must be generated alongside the mapper");
    }

    @Test
    void primitiveReadsGuardAgainstNullButBoxedReadsDoNot() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/demo4"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes4"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated4"));

        Files.writeString(sourceDir.resolve("Widget.java"), """
                package demo4;
                public class Widget {
                    public Long id;
                    public int qty;
                    public String name;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public int getQty() { return qty; }
                    public void setQty(int qty) { this.qty = qty; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        Files.writeString(sourceDir.resolve("Widgets.java"), """
                package demo4;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Widgets extends CrudRepository<Widget, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Widget.java", "Widgets.java");

        Path mapper = generatedDir.resolve("demo4/WidgetRowMapper.java");
        assertTrue(Files.exists(mapper), "Expected generated " + mapper);
        String mp = Files.readString(mapper);

        // The primitive branch must read the column, immediately check wasNull() and fail fast,
        // matching the reflective path that throws when a SQL NULL is assigned to a primitive field.
        assertTrue(mp.contains("rs.getInt("), "primitive getter missing:" + System.lineSeparator() + mp);
        assertTrue(mp.contains("rs.wasNull()"), "NULL guard missing:" + System.lineSeparator() + mp);
        assertTrue(mp.contains("throw new SQLException"), "NULL guard must throw:" + System.lineSeparator() + mp);

        // The boxed/reference branch must stay a plain getObject assignment (NULL is a legal value).
        assertTrue(mp.contains("entity.setName(rs.getObject(WidgetMetadata.NAME, String.class));"),
                "boxed read must remain unguarded:" + System.lineSeparator() + mp);
    }

    @Test
    void generatedReadsUseRowMapperButNativeQueryUsesClass() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/demo3"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes3"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated3"));

        Files.writeString(sourceDir.resolve("Book.java"), """
                package demo3;
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
                package demo3;
                import jakarta.data.repository.By;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Find;
                import jakarta.data.repository.Query;
                import jakarta.data.repository.Repository;
                import java.util.List;
                import java.util.Optional;

                @Repository
                public interface Shelf extends CrudRepository<Book, Long> {
                    @Find
                    List<Book> findByTitle(@By("title") String title);

                    @Query("SELECT id, title FROM book WHERE title = ?1")
                    Optional<Book> nativeByTitle(String title);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Book.java", "Shelf.java");

        String impl = Files.readString(generatedDir.resolve("demo3/ShelfImpl.java"));
        assertTrue(impl.contains(".query(new BookRowMapper())"),
                "convention find must use the generated mapper:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".query(Book.class)"),
                "@Query native SQL must keep class-based mapping:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("findById") && impl.contains(".query(new BookRowMapper()).optional()"),
                "findById must use the mapper:" + System.lineSeparator() + impl);
    }

    @Test
    void associationFieldContributesFkColumnAndIsNotEmbedded() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/cls"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-cls"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-cls"));

        Files.writeString(sourceDir.resolve("Author.java"), """
                package cls;
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
                package cls;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Association;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Book {
                    @Identity Long id;
                    String title;
                    @Association Author author;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public Author getAuthor() { return author; }
                    public void setAuthor(Author author) { this.author = author; }
                }
                """);
        Files.writeString(sourceDir.resolve("Books.java"), """
                package cls;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Books extends CrudRepository<Book, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Author.java", "Book.java", "Books.java");

        // The @Association field must appear as an fk column (author_id), never as flattened embedded leaves.
        String impl = Files.readString(generatedDir.resolve("cls/BooksImpl.java"));
        assertTrue(impl.contains("\"author_id\""),
                "association must contribute its fk column:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("\"title\""),
                "scalar column must be present:" + System.lineSeparator() + impl);
    }

    @Test
    void generatesRowMapperForByteArrayField() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/bytez"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-bytez"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-bytez"));

        Files.writeString(sourceDir.resolve("Blob.java"), """
                package bytez;
                public class Blob {
                    public Long id;
                    public String name;
                    public byte[] data;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public byte[] getData() { return data; }
                    public void setData(byte[] data) { this.data = data; }
                }
                """);
        Files.writeString(sourceDir.resolve("Blobs.java"), """
                package bytez;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Blobs extends CrudRepository<Blob, Long> { }
                """);

        // compile() also compiles the generated sources, so a valid byte[] read must be emitted
        // for the whole task to succeed (see RepositoryProcessor.readExpression -> getObject(byte[].class)).
        compile(sourceDir, classesDir, generatedDir, "Blob.java", "Blobs.java");

        Path metadata = generatedDir.resolve("bytez/BlobMetadata.java");
        assertTrue(Files.exists(metadata), "Expected generated " + metadata);
        String md = Files.readString(metadata);
        assertTrue(md.contains("public static final String TABLE_NAME = \"blob\";"), md);
        assertTrue(md.contains("public static final String ID = \"id\";"), md);
        assertTrue(md.contains("public static final String NAME = \"name\";"), md);
        assertTrue(md.contains("public static final String DATA = \"data\";"), md);

        Path mapper = generatedDir.resolve("bytez/BlobRowMapper.java");
        assertTrue(Files.exists(mapper),
                "a byte[] scalar is JDBC-safe and must not silently lose its generated RowMapper");
        String mp = Files.readString(mapper);
        assertTrue(mp.contains("implements RowMapper<Blob>"), mp);
        assertTrue(mp.contains("rs.getObject(BlobMetadata.ID, Long.class)"), mp);
        assertTrue(mp.contains("rs.getObject(BlobMetadata.NAME, String.class)"), mp);
        assertTrue(mp.contains("rs.getObject(BlobMetadata.DATA, byte[].class)"),
                "byte[] column must be read via getObject(byte[].class):" + System.lineSeparator() + mp);
        assertTrue(mp.contains("entity.setData("), mp);
        assertTrue(mp.contains("return entity;"), mp);
    }

    @Test
    void generatedMetadataImplementsEntityDescriptorWithKindsAndTargets() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/meta"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-meta"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-meta"));

        Files.writeString(sourceDir.resolve("Author.java"), """
                package meta;
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
                package meta;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Association;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Book {
                    @Identity Long id;
                    String title;
                    @Association Author author;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public Author getAuthor() { return author; }
                    public void setAuthor(Author author) { this.author = author; }
                }
                """);
        Files.writeString(sourceDir.resolve("Books.java"), """
                package meta;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Books extends CrudRepository<Book, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Author.java", "Book.java", "Books.java");

        String md = Files.readString(generatedDir.resolve("meta/BookMetadata.java"));
        assertTrue(md.contains("implements EntityDescriptor"),
                "generated metadata must implement the interface:" + System.lineSeparator() + md);
        assertTrue(md.contains("import io.github.fludakit.data.metadata.EntityDescriptor;"), md);
        assertTrue(md.contains("public static final BookMetadata INSTANCE"), md);
        assertTrue(md.contains("Kind.ASSOCIATION"),
                "@Association must be exposed as ASSOCIATION:" + System.lineSeparator() + md);
        assertTrue(md.contains("\"meta.Author\""),
                "association target type name must be recorded:" + System.lineSeparator() + md);
        // Plan A column constants are preserved:
        assertTrue(md.contains("public static final String TABLE_NAME = \"book\";"), md);
        assertTrue(md.contains("public static final String TITLE = \"title\";"), md);
        // The id() accessor emits the Kind.ID attribute literal (ruling 8).
        assertTrue(md.contains("public EntityDescriptor.Attribute id()"), md);
        assertTrue(md.contains("Kind.ID"), "id() must expose the ID attribute:" + System.lineSeparator() + md);
        // typeName() carries the entity FQN.
        assertTrue(md.contains("public String typeName()"), md);
        assertTrue(md.contains("\"meta.Book\""), md);
        // The SCALAR attribute literal has null targetTypeName/inverseName/embedded: a $S null crash
        // would have failed the compile() above, so reaching this line proves null-safe emission.
        assertTrue(md.contains("EntityDescriptor.Kind.SCALAR"), md);
    }

    @Test
    void embeddedValueObjectGetsMetadataWithoutTableOrId() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/emb"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-emb"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-emb"));

        Files.writeString(sourceDir.resolve("GeoPoint.java"), """
                package emb;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class GeoPoint {
                    double lat;
                    double lng;
                    public double getLat() { return lat; }
                    public void setLat(double lat) { this.lat = lat; }
                    public double getLng() { return lng; }
                    public void setLng(double lng) { this.lng = lng; }
                }
                """);
        Files.writeString(sourceDir.resolve("Address.java"), """
                package emb;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Address {
                    String city;
                    GeoPoint point;
                    public String getCity() { return city; }
                    public void setCity(String city) { this.city = city; }
                    public GeoPoint getPoint() { return point; }
                    public void setPoint(GeoPoint point) { this.point = point; }
                }
                """);
        Files.writeString(sourceDir.resolve("Contact.java"), """
                package emb;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Contact {
                    @Identity Long id;
                    String name;
                    Address address;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public Address getAddress() { return address; }
                    public void setAddress(Address address) { this.address = address; }
                }
                """);
        Files.writeString(sourceDir.resolve("Contacts.java"), """
                package emb;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Contacts extends CrudRepository<Contact, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "GeoPoint.java", "Address.java", "Contact.java", "Contacts.java");

        // The owner gate is unchanged: Contact embeds a non-JDBC-safe value object type, so it keeps
        // the reflective read path and gets no RowMapper/Metadata of its own.
        assertTrue(Files.notExists(generatedDir.resolve("emb/ContactRowMapper.java")),
                "the RowMapper gate must stay as it is for embedded value objects");
        assertTrue(Files.notExists(generatedDir.resolve("emb/ContactMetadata.java")),
                "no owner metadata when the mapper is skipped");

        // The value object gets a descriptor-only Metadata: no TABLE_NAME, null tableName(), null id().
        Path voMetadata = generatedDir.resolve("emb/AddressMetadata.java");
        assertTrue(Files.exists(voMetadata), "Expected generated " + voMetadata);
        String vmd = Files.readString(voMetadata);
        assertTrue(vmd.contains("implements EntityDescriptor"), vmd);
        assertTrue(vmd.contains("public static final AddressMetadata INSTANCE"), vmd);
        assertTrue(!vmd.contains("TABLE_NAME"),
                "a value object has no table and must not emit TABLE_NAME:" + System.lineSeparator() + vmd);
        assertTrue(vmd.contains("public String tableName()") && vmd.contains("return null;"),
                "value objects report a null table name:" + System.lineSeparator() + vmd);
        assertTrue(vmd.contains("public EntityDescriptor.Attribute id()") && vmd.contains("return null;"),
                "value objects report a null id:" + System.lineSeparator() + vmd);
        assertTrue(vmd.contains("public static final String CITY = \"city\";"), vmd);
        // The nested EMBEDDED attribute references the innermost value object's INSTANCE ...
        assertTrue(vmd.contains("GeoPointMetadata.INSTANCE"),
                "embedded attribute must reference the nested value object's INSTANCE:" + System.lineSeparator() + vmd);
        // ... which must itself have been generated (transitive EMBEDDED tree walk).
        Path innerMetadata = generatedDir.resolve("emb/GeoPointMetadata.java");
        assertTrue(Files.exists(innerMetadata), "Expected generated " + innerMetadata);
        String imd = Files.readString(innerMetadata);
        assertTrue(imd.contains("public static final String LAT = \"lat\";"), imd);
        assertTrue(imd.contains("EntityDescriptor.Kind.SCALAR"), imd);
    }

    @Test
    void genericEntityWithTypeVariableFieldDoesNotCrashCodegen() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/gentvar"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-gentvar"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-gentvar"));

        Files.writeString(sourceDir.resolve("Box.java"), """
                package gentvar;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Box<T> {
                    @Identity Long id;
                    T value;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public T getValue() { return value; }
                    public void setValue(T value) { this.value = value; }
                }
                """);
        Files.writeString(sourceDir.resolve("Boxes.java"), """
                package gentvar;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Boxes extends CrudRepository<Box, Long> { }
                """);

        // Processing a TypeVariable field must not abort annotation processing; the mapper gate then
        // silently skips the non-JDBC-safe field as before (ruling 5).
        compile(sourceDir, classesDir, generatedDir, "Box.java", "Boxes.java");

        assertTrue(Files.exists(generatedDir.resolve("gentvar/BoxesImpl.java")),
                "repository impl must still be generated");
        assertTrue(Files.notExists(generatedDir.resolve("gentvar/BoxRowMapper.java")),
                "a T-typed field is not JDBC-safe, so the RowMapper must be skipped");
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
    void bareEntityFieldAndEntityListClassifyAsManyToOneAndOneToManyWithResolvedInverse() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rel"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rel"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rel"));

        Files.writeString(sourceDir.resolve("Child.java"), """
                package rel;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Child {
                    @Identity Long id;
                    Parent owner;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Parent getOwner() { return owner; }
                    public void setOwner(Parent owner) { this.owner = owner; }
                }
                """);
        Files.writeString(sourceDir.resolve("Parent.java"), """
                package rel;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @AggregateRoot
                public class Parent {
                    @Identity Long id;
                    String name;
                    Child primary;
                    List<Child> children = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public Child getPrimary() { return primary; }
                    public void setPrimary(Child primary) { this.primary = primary; }
                    public List<Child> getChildren() { return children; }
                    public void setChildren(List<Child> children) { this.children = children; }
                }
                """);
        Files.writeString(sourceDir.resolve("Parents.java"), """
                package rel;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Parents extends CrudRepository<Parent, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Child.java", "Parent.java", "Parents.java");

        // Flatten JavaPoet's soft wraps so the positional literals can be pinned verbatim: five of
        // the seven Attribute components are Strings, so ONLY an exact full-literal assertion
        // detects a swapped column/targetTypeName/inverseName argument (M-6).
        String md = Files.readString(generatedDir.resolve("rel/ParentMetadata.java")).replaceAll("\\s+", " ");

        // Bare entity-typed field: MANY_TO_ONE + fk column + target FQN (coverage lock, I-2).
        assertTrue(md.contains("new EntityDescriptor.Attribute(\"primary\", \"rel.Child\","
                        + " EntityDescriptor.Kind.MANY_TO_ONE, \"primary_id\", \"rel.Child\", null, null)"),
                "un-annotated entity field must be MANY_TO_ONE with an fk column:" + System.lineSeparator() + md);
        assertTrue(md.contains("public static final String PRIMARY = \"primary_id\";"),
                "the fk column constant must be emitted from the Property list:" + System.lineSeparator() + md);

        // List<@Entity>: ONE_TO_MANY via the second pass, column null, inverse resolved from the
        // child's own MANY_TO_ONE back-reference (coverage lock, I-2 + positional lock, M-6).
        assertTrue(md.contains("new EntityDescriptor.Attribute(\"children\", \"java.util.List<rel.Child>\","
                        + " EntityDescriptor.Kind.ONE_TO_MANY, null, \"rel.Child\", \"owner\", null)"),
                "entity list must be ONE_TO_MANY with the resolved inverse name:" + System.lineSeparator() + md);
        assertTrue(!md.contains("public static final String CHILDREN"),
                "ONE_TO_MANY contributes no column constant:" + System.lineSeparator() + md);

        // Association properties are skipped by the mapper gate, so the owner still generates.
        assertTrue(Files.exists(generatedDir.resolve("rel/ParentRowMapper.java")),
                "entity-typed fields are association-backed and must not abort the owner's gate");
    }

    @Test
    void embeddedPropertyGetsNoPhantomColumnConstant() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/phantom"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-phantom"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-phantom"));

        Files.writeString(sourceDir.resolve("GeoPoint.java"), """
                package phantom;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class GeoPoint {
                    double lat;
                    double lng;
                    public double getLat() { return lat; }
                    public void setLat(double lat) { this.lat = lat; }
                    public double getLng() { return lng; }
                    public void setLng(double lng) { this.lng = lng; }
                }
                """);
        Files.writeString(sourceDir.resolve("Address.java"), """
                package phantom;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Address {
                    String city;
                    GeoPoint point;
                    public String getCity() { return city; }
                    public void setCity(String city) { this.city = city; }
                    public GeoPoint getPoint() { return point; }
                    public void setPoint(GeoPoint point) { this.point = point; }
                }
                """);
        Files.writeString(sourceDir.resolve("Contact.java"), """
                package phantom;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Contact {
                    @Identity Long id;
                    String name;
                    Address address;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public Address getAddress() { return address; }
                    public void setAddress(Address address) { this.address = address; }
                }
                """);
        Files.writeString(sourceDir.resolve("Contacts.java"), """
                package phantom;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Contacts extends CrudRepository<Contact, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "GeoPoint.java", "Address.java", "Contact.java", "Contacts.java");

        String vmd = Files.readString(generatedDir.resolve("phantom/AddressMetadata.java"));
        // M-5: the EMBEDDED property's column slot is a leaf PREFIX, not a physical column named
        // "point"; the public generated API must not advertise a phantom column constant for it.
        assertTrue(!vmd.contains("public static final String POINT"),
                "no phantom column constant for an EMBEDDED property:" + System.lineSeparator() + vmd);
        // Real (leaf-ward) constants remain.
        assertTrue(vmd.contains("public static final String CITY = \"city\";"), vmd);
        assertTrue(vmd.contains("GeoPointMetadata.INSTANCE"),
                "the EMBEDDED attribute must still reference the nested value object:" + System.lineSeparator() + vmd);
        Path inner = generatedDir.resolve("phantom/GeoPointMetadata.java");
        assertTrue(Files.exists(inner), "Expected generated " + inner);
        String imd = Files.readString(inner);
        assertTrue(imd.contains("public static final String LAT = \"lat\";"), imd);
        assertTrue(imd.contains("public static final String LNG = \"lng\";"), imd);
    }

    private void compile(Path sourceDir, Path classesDir, Path generatedDir, String... fileNames) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A system Java compiler is required to run this test");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            java.util.List<java.io.File> files = new java.util.ArrayList<>();
            for (String n : fileNames) files.add(sourceDir.resolve(n).toFile());
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(files.toArray(new java.io.File[0]));
            List<String> options = List.of(
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", classesDir.toString(),
                    "-s", generatedDir.toString(),
                    "-processor", "io.github.fludakit.data.processor.RepositoryProcessor");
            boolean success = compiler.getTask(null, fm, diagnostics, options, null, units).call();
            String messages = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(Object::toString).collect(Collectors.joining(System.lineSeparator()));
            assertTrue(success, "Annotation processing failed:" + System.lineSeparator() + messages);
        }
    }
}
