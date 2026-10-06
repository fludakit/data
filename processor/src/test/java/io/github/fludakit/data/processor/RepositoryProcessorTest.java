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
import java.util.Locale;
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
        assertTrue(md.contains("implements EntityDescriptor<Book>"),
                "generated metadata must implement the generic interface:" + System.lineSeparator() + md);
        assertTrue(md.contains("import io.github.fludakit.data.metadata.EntityDescriptor;"), md);
        assertTrue(md.contains("public static final BookMetadata INSTANCE"), md);
        // Decision 1: the descriptor returns the _Book metamodel field instances, not literals,
        // and the association target is a Class literal on the typed FK field.
        assertTrue(md.contains("_Book.author"),
                "@Association must be exposed through the FK-typed metamodel field:" + System.lineSeparator() + md);
        String mm = Files.readString(generatedDir.resolve("meta/_Book.java")).replaceAll("\\s+", "");
        assertTrue(mm.contains("ForeignKeyAttribute<Book,Author>author="
                        + "newForeignKeyAttributeRecord<>(AUTHOR,\"author_id\",Author.class,()->null);"),
                "association target must be a Class literal, not an FQN string:" + System.lineSeparator() + mm);
        // Plan A column constants are preserved:
        assertTrue(md.contains("public static final String TABLE_NAME = \"book\";"), md);
        assertTrue(md.contains("public static final String TITLE = \"title\";"), md);
        // The id() accessor returns the typed metamodel id field (ruling 8).
        assertTrue(md.contains("public IdAttribute<Book> id()"), md);
        assertTrue(md.contains("return _Book.id;"), "id() must expose the ID attribute:" + System.lineSeparator() + md);
        // entityClass() replaces the old typeName() string.
        assertTrue(md.contains("public Class<Book> entityClass()"), md);
        assertTrue(md.contains("return Book.class;"), md);
        // The scalar field rides the same metamodel reference list: a $S null crash would have
        // failed the compile() above, so reaching this line proves null-safe emission.
        assertTrue(md.contains("_Book.title"), md);
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

        // Post-Task-2 the gate passes for embeddable value objects: Contact is fully JDBC-safe or
        // embeddable, so owner-level Metadata and a reflection-free RowMapper are generated too.
        assertTrue(Files.exists(generatedDir.resolve("emb/ContactRowMapper.java")),
                "all-safe-or-embedded entities must get the generated mapper");
        assertTrue(Files.exists(generatedDir.resolve("emb/ContactMetadata.java")),
                "owner metadata must be generated alongside the mapper");

        // The value object gets a descriptor-only Metadata: no TABLE_NAME, null tableName(), null id().
        // Whitespace collapsed so the record-literal pins survive JavaPoet soft wraps.
        Path voMetadata = generatedDir.resolve("emb/AddressMetadata.java");
        assertTrue(Files.exists(voMetadata), "Expected generated " + voMetadata);
        String vmd = Files.readString(voMetadata).replaceAll("\\s+", " ");
        assertTrue(vmd.contains("implements EntityDescriptor<Address>"), vmd);
        assertTrue(vmd.contains("public static final AddressMetadata INSTANCE"), vmd);
        assertTrue(!vmd.contains("TABLE_NAME"),
                "a value object has no table and must not emit TABLE_NAME:" + System.lineSeparator() + vmd);
        assertTrue(vmd.contains("public String tableName()") && vmd.contains("return null;"),
                "value objects report a null table name:" + System.lineSeparator() + vmd);
        // A VO has no metamodel, so its members are fresh attribute-record literals (Decision 1's
        // fallback mode) and the id accessor is a typed null.
        assertTrue(vmd.contains("public IdAttribute<Address> id()") && vmd.contains("return null;"),
                "value objects report a null id:" + System.lineSeparator() + vmd);
        assertTrue(vmd.contains("public static final String CITY = \"city\";"), vmd);
        // The nested EMBEDDED attribute references the innermost value object's INSTANCE ...
        assertTrue(vmd.contains("GeoPointMetadata.INSTANCE"),
                "embedded attribute must reference the nested value object's INSTANCE:" + System.lineSeparator() + vmd);
        assertTrue(vmd.contains("new EmbeddedAttributeRecord<Address,GeoPoint>(\"point\", GeoPointMetadata.INSTANCE)"),
                "the embedded literal must carry the VO type arguments:" + System.lineSeparator() + vmd);
        // ... which must itself have been generated (transitive EMBEDDED tree walk).
        Path innerMetadata = generatedDir.resolve("emb/GeoPointMetadata.java");
        assertTrue(Files.exists(innerMetadata), "Expected generated " + innerMetadata);
        String imd = Files.readString(innerMetadata).replaceAll("\\s+", " ");
        assertTrue(imd.contains("public static final String LAT = \"lat\";"), imd);
        assertTrue(imd.contains("new SortableColumnAttributeRecord<GeoPoint>(\"lat\", \"lat\")"), imd);
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
        // M-3: pin the emitted hydration SELECT constant value exactly (the ASSOCIATION `product` column is
        // excluded, so the list is parentFk + the flattened SCALAR `quantity`), not just the loose `IN (` token.
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

        // I-2: the hydrated @Find branches (LIST / STREAM / OPTIONAL / SINGLE) each wrap the umbrella.
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
        // the @Association target (Decision 8: many-to-one/association children stay null in this task).
        String voMapper = Files.readString(generatedDir.resolve("hyd/LineRowMapper.java"));
        assertTrue(voMapper.contains("rs.getInt(LineMetadata.QUANTITY)"),
                "primitive leaf must use rs.getInt(...) with the VO's own metadata:" + System.lineSeparator() + voMapper);
        assertTrue(voMapper.contains("if (rs.wasNull())"),
                "primitive read must guard wasNull:" + System.lineSeparator() + voMapper);
        assertTrue(!voMapper.contains("getProduct"),
                "@Association child target is never hydrated in Task 3:" + System.lineSeparator() + voMapper);

        assertTrue(!impl.contains("java.lang.reflect"), "generated code must stay reflection-free:" + System.lineSeparator() + impl);
    }

    @Test
    void cascadeCoversGateFailedCollectionWithoutLoader() throws Exception {
        // I-1: save() inserts child rows for EVERY collection (appendInsertStatements drives from
        // entity.collections()), so update() and deleteById() must cascade the DELETE for every
        // collection TOO — even one whose VO fails the row-mapper gate. Gate failure may only degrade
        // HYDRATION (no loader, no umbrella), never cascade integrity, else the child rows orphan.
        // A VO with a non-JDBC-safe enum property fails canGenerateRowMapper, exercising that path.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/casc"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-casc"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-casc"));

        Files.writeString(sourceDir.resolve("Status.java"), """
                package casc;
                public enum Status { OPEN, CLOSED }
                """);
        Files.writeString(sourceDir.resolve("Line.java"), """
                package casc;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Line {
                    int quantity;
                    Status flag;
                    public int getQuantity() { return quantity; }
                    public void setQuantity(int quantity) { this.quantity = quantity; }
                    public Status getFlag() { return flag; }
                    public void setFlag(Status flag) { this.flag = flag; }
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
                sourceDir, classesDir, generatedDir, "Status.java", "Line.java", "Basket.java", "Baskets.java");

        String impl = Files.readString(generatedDir.resolve("casc/BasketsImpl.java")).replace("\\\"", "\"");

        // Save still inserts child rows for the gate-failed collection (unchanged write side).
        assertTrue(impl.contains("INSERT INTO \"basket_lines\""),
                "child rows must be inserted for every collection:" + System.lineSeparator() + impl);

        // I-1: the DELETE cascade (replace-all update + deleteById) is emitted for the gate-failed collection.
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

        // The gate skip is still recorded as a NOTE (Decision 7 spirit).
        String notes = notesOf(diagnostics);
        assertTrue(notes.contains("not reflection-free mappable"),
                "gate-skip NOTE must be recorded:" + System.lineSeparator() + notes);
    }

    @Test
    void collectionLoaderKeyFollowsIntegerIdType() throws Exception {
        // I-2: the collection loader must derive the parent-key / child-FK type from the entity id,
        // not hardcode Long. An Integer-id entity with an element collection would emit a loader using
        // List<Long> keys / getObject(fk, Long.class) / (Long) row[0] / Map<Long, ...> and fail to
        // compile in the consumer build. The compile step below is the RED proof at BASE (the generated
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
        // Task 4 NEW-I: the generated-key coercion is now id-aware, so the Integer-id impl compiles
        // FULLY. This test was written at Task 3 to force this upgrade (the residual-error canary below
        // asserted the OLD `longValue()` coercion still failed the whole compile); the flip to a full-
        // compile assertion is the deliberate upgrade the Task-3 re-review mandated.
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

        // NEW-I: id-aware generated-key coercion. Integer id now binds intValue(), not longValue().
        assertTrue(impl.contains("entity.setId(((Number) keys.getKey()).intValue())"),
                "insert coercion must follow the id type:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("longValue"),
                "Integer id must not use the Long coercion:" + System.lineSeparator() + impl);

        // The whole impl now compiles: the loader AND the previously-blocking insert coercion are both
        // id-correct, so there are no residual javac ERRORs (the flipped Task-3 canary).
        List<Diagnostic<? extends JavaFileObject>> errors = diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .collect(Collectors.toList());
        assertTrue(errors.isEmpty(),
                "Integer-id impl must now fully compile, got:" + System.lineSeparator() + errors);
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
        Files.writeString(sourceDir.resolve("ParentRepository.java"), """
                package rel;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface ParentRepository extends CrudRepository<Parent, Long> { }
                """);
        // Child must be a MANAGED target (has its own @Repository) or the managed-target gate (Decision 7)
        // leaves Parent.primary/Parent.children unmapped. ChildRepository also makes rel.Parent a managed
        // target so Child.owner hydrates on the child side.
        Files.writeString(sourceDir.resolve("ChildRepository.java"), """
                package rel;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface ChildRepository extends CrudRepository<Child, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Child.java", "Parent.java", "ParentRepository.java", "ChildRepository.java");

        // Flatten JavaPoet's soft wraps so the positional pins hold verbatim: the FK column and the
        // resolved OTM inverse now live in the attribute RECORD ARGUMENTS on _Parent (Decision 1),
        // so ONLY an exact metamodel-literal assertion detects a swapped column/target/inverse
        // argument (M-6).
        String pm = Files.readString(generatedDir.resolve("rel/_Parent.java")).replaceAll("\\s+", "");
        assertTrue(pm.contains("ForeignKeyAttribute<Parent,Child>primary="
                        + "newForeignKeyAttributeRecord<>(PRIMARY,\"primary_id\",Child.class,()->ChildMetadata.INSTANCE);"),
                "un-annotated entity field must be a FK attribute with an fk column:" + System.lineSeparator() + pm);
        assertTrue(pm.contains("OneToManyAttribute<Parent,Child>children="
                        + "newOneToManyAttributeRecord<>(CHILDREN,Child.class,\"owner\",()->ChildMetadata.INSTANCE);"),
                "entity list must be ONE_TO_MANY with the resolved inverse name:" + System.lineSeparator() + pm);

        String md = Files.readString(generatedDir.resolve("rel/ParentMetadata.java")).replaceAll("\\s+", " ");
        assertTrue(md.contains("return List.of(_Parent.name, _Parent.primary, _Parent.children);"),
                "attributes() must return the metamodel field instances, not literals:" + System.lineSeparator() + md);
        assertTrue(md.contains("public static final String PRIMARY = \"primary_id\";"),
                "the fk column constant must be emitted from the Property list:" + System.lineSeparator() + md);
        assertTrue(!md.contains("public static final String CHILDREN"),
                "ONE_TO_MANY contributes no column constant:" + System.lineSeparator() + md);

        // Association properties are skipped by the mapper gate, so the owner still generates.
        assertTrue(Files.exists(generatedDir.resolve("rel/ParentRowMapper.java")),
                "entity-typed fields are association-backed and must not abort the owner's gate");

        // ---- Task 4: relationship hydration loaders (managed targets rel.Child / rel.Parent) ----
        // Normalise JavaPoet's escaped quotes and soft wraps so the SQL string literals pin verbatim.
        String parentImpl = Files.readString(generatedDir.resolve("rel/ParentRepositoryImpl.java"))
                .replace("\\\"", "\"");
        // ONE_TO_MANY (Parent.children, inverse "owner" → fk "owner_id" on the child's own table):
        // single-phase, group by the inverse fk, delegate to the child's generated entity RowMapper.
        assertTrue(parentImpl.contains("private void hydrateChildren(Collection<Parent> parents)"),
                "missing one-to-many loader:" + System.lineSeparator() + parentImpl);
        assertTrue(parentImpl.contains("SELECT \"id\", \"owner_id\" FROM \"child\" WHERE \"owner_id\" IN ("),
                "one-to-many SELECT must list child columns incl. the inverse fk:" + System.lineSeparator() + parentImpl);
        assertTrue(parentImpl.contains("new ChildRowMapper().mapRow(rs, rowNum)"),
                "one-to-many loader must pair child rows through the child entity row mapper:"
                        + System.lineSeparator() + parentImpl);
        assertTrue(parentImpl.contains("parent.setChildren(grouped.getOrDefault(parent.getId(), new ArrayList<>()))"),
                "one-to-many loader must group children onto each parent:" + System.lineSeparator() + parentImpl);
        // MANY_TO_ONE (Parent.primary, target Child, fk "primary_id" on the PARENT table): two-phase.
        // Phase 1 re-reads id + fk from the parent table; phase 2 fetches targets by distinct fk.
        assertTrue(parentImpl.contains("private void hydratePrimary(Collection<Parent> parents)"),
                "missing many-to-one loader:" + System.lineSeparator() + parentImpl);
        assertTrue(parentImpl.contains("SELECT \"id\", \"primary_id\" FROM \"parent\" WHERE \"id\" IN ("),
                "many-to-one phase 1 must re-read the fk from the parent table:" + System.lineSeparator() + parentImpl);
        assertTrue(parentImpl.contains("SQL_FIND_CHILD_BY_IDS"),
                "many-to-one phase 2 must fetch targets by their ids:" + System.lineSeparator() + parentImpl);
        assertTrue(parentImpl.contains("SELECT \"id\", \"owner_id\" FROM \"child\" WHERE \"id\" IN ("),
                "many-to-one phase 2 SELECT lists target columns from the target table:"
                        + System.lineSeparator() + parentImpl);
        assertTrue(parentImpl.contains(".query(new ChildRowMapper()).list()"),
                "many-to-one phase 2 must map targets with the target row mapper:" + System.lineSeparator() + parentImpl);
        // The umbrella covers both loaders.
        assertTrue(parentImpl.contains("private void hydrateAll(Collection<Parent> parents)"),
                "missing umbrella hydrateAll:" + System.lineSeparator() + parentImpl);
        assertTrue(parentImpl.contains("hydrateChildren(parents)") && parentImpl.contains("hydratePrimary(parents)"),
                "umbrella must call each relationship loader:" + System.lineSeparator() + parentImpl);
        // Write path (Step 6): the MANY_TO_ONE fk binding flows through boundValue (Task 2), asserted not rebuilt.
        assertTrue(parentImpl.contains(".param(\"primary\", entity.getPrimary() == null ? null : entity.getPrimary().getId())"),
                "many-to-one write must bind the target id through boundValue:" + System.lineSeparator() + parentImpl);

        // Child.owner is MANY_TO_ONE into the managed rel.Parent → hydrateOwner on the child side,
        // phase 2 fetching parents via the parent's generated row mapper (ParentRowMapper).
        String childImpl = Files.readString(generatedDir.resolve("rel/ChildRepositoryImpl.java"))
                .replace("\\\"", "\"");
        assertTrue(childImpl.contains("private void hydrateOwner(Collection<Child> parents)"),
                "missing many-to-one loader on the child side:" + System.lineSeparator() + childImpl);
        assertTrue(childImpl.contains(".query(new ParentRowMapper()).list()"),
                "child hydrateOwner must fetch parent targets with the parent row mapper:"
                        + System.lineSeparator() + childImpl);
        assertTrue(childImpl.contains("parent.setOwner(byId.get(fkByParent.get(parent.getId())))"),
                "child hydrateOwner must set the reference from the fetched target:" + System.lineSeparator() + childImpl);
        assertTrue(!childImpl.contains("java.lang.reflect"),
                "generated loaders must stay reflection-free:" + System.lineSeparator() + childImpl);
    }

    @Test
    void unmanagedManyToOneTargetIsLeftUnmappedWithNote() throws Exception {
        // Decision 7 managed-target gate: a relationship whose TARGET has no @Repository in the round
        // must NOT get a loader and must record a NOTE, so hydration is skipped rather than generating
        // a call to a row mapper that was never emitted.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/mg"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-mg"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-mg"));

        Files.writeString(sourceDir.resolve("Sibling.java"), """
                package mg;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Sibling {
                    @Identity Long id;
                    String label;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                }
                """);
        Files.writeString(sourceDir.resolve("Holder.java"), """
                package mg;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Holder {
                    @Identity Long id;
                    Sibling peer;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Sibling getPeer() { return peer; }
                    public void setPeer(Sibling peer) { this.peer = peer; }
                }
                """);
        // ONLY Holder is managed; Sibling has no repository.
        Files.writeString(sourceDir.resolve("Holders.java"), """
                package mg;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Holders extends CrudRepository<Holder, Long> { }
                """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(
                sourceDir, classesDir, generatedDir, "Sibling.java", "Holder.java", "Holders.java");

        String impl = Files.readString(generatedDir.resolve("mg/HoldersImpl.java")).replace("\\\"", "\"");
        assertTrue(!impl.contains("hydratePeer"),
                "unmanaged many-to-one target must not get a loader:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("hydrateAll"),
                "no umbrella when nothing hydrates:" + System.lineSeparator() + impl);
        String notes = notesOf(diagnostics);
        assertTrue(notes.contains("relationship 'peer' on mg.Holder: target mg.Sibling has no generated row mapper"),
                "managed-target gate NOTE must be recorded:" + System.lineSeparator() + notes);
    }

    @Test
    void generatedMapperAndBindingsCoverEmbeddedValueObject() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/gp"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-gp"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-gp"));

        Files.writeString(sourceDir.resolve("Profile.java"), """
                package gp;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Profile {
                    @Identity Long id;
                    Address address;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Address getAddress() { return address; }
                    public void setAddress(Address address) { this.address = address; }
                }
                """);
        Files.writeString(sourceDir.resolve("Address.java"), """
                package gp;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Address {
                    String city;
                    Integer zip;
                    GeoPoint point;
                    public String getCity() { return city; }
                    public void setCity(String city) { this.city = city; }
                    public Integer getZip() { return zip; }
                    public void setZip(Integer zip) { this.zip = zip; }
                    public GeoPoint getPoint() { return point; }
                    public void setPoint(GeoPoint point) { this.point = point; }
                }
                """);
        Files.writeString(sourceDir.resolve("GeoPoint.java"), """
                package gp;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class GeoPoint {
                    Double lat;
                    Double lng;
                    public Double getLat() { return lat; }
                    public void setLat(Double lat) { this.lat = lat; }
                    public Double getLng() { return lng; }
                    public void setLng(Double lng) { this.lng = lng; }
                }
                """);
        Files.writeString(sourceDir.resolve("ProfileRepository.java"), """
                package gp;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository interface ProfileRepository extends CrudRepository<Profile, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Profile.java", "Address.java", "GeoPoint.java", "ProfileRepository.java");

        // The gate now passes for all-boxed embedded value objects, and the generated mapper builds
        // the VO leaf-by-leaf from the flattened columns — reflection-free (Task 2).
        String mapper = Files.readString(generatedDir.resolve("gp/ProfileRowMapper.java"));
        assertTrue(mapper.contains("String address_city = rs.getObject(ProfileMetadata.ADDRESS_CITY, String.class);"),
                "flattened boxed leaf must be read into a prefixed local:" + System.lineSeparator() + mapper);
        // I-1 / Decision 2 per-subtree presence: the nested GeoPoint child is constructed AND set only
        // when a leaf of ITS subtree is non-null — the guard lists the point leaves alone, not the
        // address scalars (case B: any point leaf set constructs the child). An all-null point subtree
        // must leave the child null with setPoint never called (case A).
        assertTrue(mapper.contains("if (address_point_lat != null || address_point_lng != null) {\n"
                        + "        GeoPoint address_point = new GeoPoint();\n"
                        + "        address_point.setLat(address_point_lat);\n"
                        + "        address_point.setLng(address_point_lng);\n"
                        + "        address.setPoint(address_point);\n"
                        + "      }"),
                "nested child VO must be constructed and set under its own subtree presence guard:"
                        + System.lineSeparator() + mapper);
        assertTrue(!mapper.contains("GeoPoint address_point = new GeoPoint();\n"
                        + "      address_point.setLat(address_point_lat);\n"
                        + "      address_point.setLng(address_point_lng);\n"
                        + "      Address address = new Address();"),
                "nested child VO must not be constructed unconditionally outside its subtree guard:"
                        + System.lineSeparator() + mapper);
        assertTrue(mapper.contains("entity.setAddress(address);"),
                "the embedded property must be assigned on the entity:" + System.lineSeparator() + mapper);
        assertTrue(!mapper.contains("getDeclaredField"), "mapper must not reflect:" + System.lineSeparator() + mapper);
        assertTrue(!mapper.contains("java.lang.reflect"), "mapper must not import reflection:" + System.lineSeparator() + mapper);

        // Write bindings iterate the descriptor's flattened columns; param names follow
        // SqlBuilder.paramName(path) (dot to underscore) and every intermediate null short-circuits.
        String impl = Files.readString(generatedDir.resolve("gp/ProfileRepositoryImpl.java"));
        assertTrue(impl.contains(".param(\"address_city\", entity.getAddress() == null ? null : entity.getAddress().getCity())"),
                "embedded leaf must bind through its null-guarded path:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"address_point_lat\", entity.getAddress() == null"
                        + " || entity.getAddress().getPoint() == null ? null : entity.getAddress().getPoint().getLat())"),
                "nested embedded leaf must chain the guards:" + System.lineSeparator() + impl);
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
        // Pinned to the exact phantom constant so the flattened leaf constants (below) still pass:
        // the intent is "no constant for the embedded property itself", not "no POINT* constant".
        assertTrue(!vmd.contains("public static final String POINT = "),
                "no phantom column constant for an EMBEDDED property:" + System.lineSeparator() + vmd);
        // Real (leaf-ward) constants remain.
        assertTrue(vmd.contains("public static final String CITY = \"city\";"), vmd);
        // leaf constants exist and match the flattened columns descriptor rendering produced in Plan B:
        // one constant per physical leaf column, named <leaf-prefix>_<leaf> from the VO's own
        // EMBEDDED attribute (Decision 3; the owner-level chain e.g. ADDRESS_POINT_LAT rides the
        // same loop, but the owner gate skips ContactMetadata here, so this fixture pins the VO level).
        assertTrue(vmd.contains("public static final String POINT_LAT = \"point_lat\";"),
                "flattened embedded leaf must get a column constant:" + System.lineSeparator() + vmd);
        assertTrue(vmd.contains("public static final String POINT_LNG = \"point_lng\";"),
                "flattened embedded leaf must get a column constant:" + System.lineSeparator() + vmd);
        assertTrue(vmd.contains("GeoPointMetadata.INSTANCE"),
                "the EMBEDDED attribute must still reference the nested value object:" + System.lineSeparator() + vmd);
        Path inner = generatedDir.resolve("phantom/GeoPointMetadata.java");
        assertTrue(Files.exists(inner), "Expected generated " + inner);
        String imd = Files.readString(inner);
        assertTrue(imd.contains("public static final String LAT = \"lat\";"), imd);
        assertTrue(imd.contains("public static final String LNG = \"lng\";"), imd);

        // The owner entity now passes the post-Task-2 gate (city is a boxed String, the primitive
        // double leaves are JDBC-safe), so the full flattened chain surfaces on ContactMetadata —
        // the owner-level constants lock Task 1 could not make when the gate skipped the owner.
        String cmd = Files.readString(generatedDir.resolve("phantom/ContactMetadata.java"));
        assertTrue(cmd.contains("public static final String ADDRESS_CITY = \"address_city\";"),
                "owner-level leaf constant must carry the embedded prefix:" + System.lineSeparator() + cmd);
        assertTrue(cmd.contains("public static final String ADDRESS_POINT_LAT = \"address_point_lat\";"),
                "owner-level nested leaf constant must chain both prefixes:" + System.lineSeparator() + cmd);
        assertTrue(cmd.contains("public static final String ADDRESS_POINT_LNG = \"address_point_lng\";"),
                "owner-level nested leaf constant must chain both prefixes:" + System.lineSeparator() + cmd);
    }

    @Test
    void staticMetamodelHasSpecShapeForEntityAttributes() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/mmbook"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-mmbook"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-mmbook"));

        Files.writeString(sourceDir.resolve("Book.java"), """
                package mmbook;

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
                package mmbook;

                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;

                @Repository
                public interface Shelf extends CrudRepository<Book, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Book.java", "Shelf.java");

        Path metamodel = generatedDir.resolve("mmbook/_Book.java");
        assertTrue(Files.exists(metamodel), "Expected generated " + metamodel);
        // Whitespace-stripped so JavaPoet's soft wraps cannot break the positional pins.
        String mm = Files.readString(metamodel).replaceAll("\\s+", "");
        assertTrue(mm.contains("publicinterface_Book"), mm);
        assertTrue(mm.contains("@StaticMetamodel(Book.class)"), mm);
        assertTrue(mm.contains("@Generated(\"io.github.fludakit.data.processor.RepositoryProcessor\")"), mm);
        assertTrue(mm.contains("StringID=\"id\";"), mm);
        assertTrue(mm.contains("StringTITLE=\"title\";"), mm);
        assertTrue(mm.contains("IdAttribute<Book>id=newIdAttributeRecord<>(ID,\"id\");"), mm);
        assertTrue(mm.contains("TextColumnAttribute<Book>title=newTextColumnAttributeRecord<>(TITLE,\"title\");"), mm);
        // The metamodel is additive: the internal descriptor class remains.
        assertTrue(Files.exists(generatedDir.resolve("mmbook/BookMetadata.java")),
                "the internal <Entity>Metadata must still be generated");
    }

    @Test
    void staticMetamodelEmbeddedLeavesBecomeDotPathAttributes() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/mmgp"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-mmgp"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-mmgp"));

        Files.writeString(sourceDir.resolve("Profile.java"), """
                package mmgp;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Profile {
                    @Identity Long id;
                    Address address;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Address getAddress() { return address; }
                    public void setAddress(Address address) { this.address = address; }
                }
                """);
        Files.writeString(sourceDir.resolve("Address.java"), """
                package mmgp;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Address {
                    String city;
                    Integer zip;
                    GeoPoint point;
                    public String getCity() { return city; }
                    public void setCity(String city) { this.city = city; }
                    public Integer getZip() { return zip; }
                    public void setZip(Integer zip) { this.zip = zip; }
                    public GeoPoint getPoint() { return point; }
                    public void setPoint(GeoPoint point) { this.point = point; }
                }
                """);
        Files.writeString(sourceDir.resolve("GeoPoint.java"), """
                package mmgp;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class GeoPoint {
                    Double lat;
                    Double lng;
                    public Double getLat() { return lat; }
                    public void setLat(Double lat) { this.lat = lat; }
                    public Double getLng() { return lng; }
                    public void setLng(Double lng) { this.lng = lng; }
                }
                """);
        Files.writeString(sourceDir.resolve("ProfileRepository.java"), """
                package mmgp;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository interface ProfileRepository extends CrudRepository<Profile, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Profile.java", "Address.java", "GeoPoint.java", "ProfileRepository.java");

        String mm = Files.readString(generatedDir.resolve("mmgp/_Profile.java")).replaceAll("\\s+", "");
        assertTrue(mm.contains("StringADDRESS=\"address\";"), mm);
        assertTrue(mm.contains("StringADDRESS_CITY=\"address.city\";"), mm);
        assertTrue(mm.contains("StringADDRESS_ZIP=\"address.zip\";"), mm);
        assertTrue(mm.contains("StringADDRESS_POINT=\"address.point\";"), mm);
        assertTrue(mm.contains("StringADDRESS_POINT_LAT=\"address.point.lat\";"), mm);
        assertTrue(mm.contains("StringADDRESS_POINT_LNG=\"address.point.lng\";"), mm);
        assertTrue(mm.contains("EmbeddedAttribute<Profile,Address>address="
                + "newEmbeddedAttributeRecord<>(ADDRESS,AddressMetadata.INSTANCE);"), mm);
        assertTrue(mm.contains("TextColumnAttribute<Profile>address_city="
                + "newTextColumnAttributeRecord<>(ADDRESS_CITY,\"address_city\");"), mm);
        assertTrue(mm.contains("SortableColumnAttribute<Profile>address_zip="
                + "newSortableColumnAttributeRecord<>(ADDRESS_ZIP,\"address_zip\");"), mm);
        assertTrue(mm.contains("EmbeddedAttribute<Profile,GeoPoint>address_point="
                + "newEmbeddedAttributeRecord<>(ADDRESS_POINT,GeoPointMetadata.INSTANCE);"), mm);
        assertTrue(mm.contains("SortableColumnAttribute<Profile>address_point_lat="
                + "newSortableColumnAttributeRecord<>(ADDRESS_POINT_LAT,\"address_point_lat\");"), mm);
        assertTrue(mm.contains("IdAttribute<Profile>id=newIdAttributeRecord<>(ID,\"id\");"), mm);
        // Value objects never get their own metamodel classes (aggregate roots only).
        assertTrue(!Files.exists(generatedDir.resolve("mmgp/_Address.java")),
                "no _VO class for a value object");
        assertTrue(!Files.exists(generatedDir.resolve("mmgp/_GeoPoint.java")),
                "no _VO class for a nested value object");
    }

    @Test
    void staticMetamodelCoversRelationshipsAndSortSwitchResolvesDotPaths() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/mmrel"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-mmrel"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-mmrel"));

        Files.writeString(sourceDir.resolve("Parent.java"), """
                package mmrel;
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
        Files.writeString(sourceDir.resolve("Child.java"), """
                package mmrel;
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
        Files.writeString(sourceDir.resolve("ParentRepository.java"), """
                package mmrel;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface ParentRepository extends CrudRepository<Parent, Long> { }
                """);
        Files.writeString(sourceDir.resolve("ChildRepository.java"), """
                package mmrel;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface ChildRepository extends CrudRepository<Child, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Parent.java", "Child.java", "ParentRepository.java", "ChildRepository.java");

        // Relationship properties surface as typed attribute fields, keyed by ATTRIBUTE name.
        String pm = Files.readString(generatedDir.resolve("mmrel/_Parent.java")).replaceAll("\\s+", "");
        assertTrue(pm.contains("StringCHILDREN=\"children\";"), pm);
        assertTrue(pm.contains("OneToManyAttribute<Parent,Child>children="
                + "newOneToManyAttributeRecord<>(CHILDREN,Child.class,\"owner\",()->ChildMetadata.INSTANCE);"), pm);
        assertTrue(pm.contains("StringPRIMARY=\"primary\";"), pm);
        assertTrue(pm.contains("ForeignKeyAttribute<Parent,Child>primary="
                + "newForeignKeyAttributeRecord<>(PRIMARY,\"primary_id\",Child.class,()->ChildMetadata.INSTANCE);"), pm);
        assertTrue(pm.contains("TextColumnAttribute<Parent>name=newTextColumnAttributeRecord<>(NAME,\"name\");"), pm);
        String cm = Files.readString(generatedDir.resolve("mmrel/_Child.java")).replaceAll("\\s+", "");
        assertTrue(cm.contains("ForeignKeyAttribute<Child,Parent>owner="
                + "newForeignKeyAttributeRecord<>(OWNER,\"owner_id\",Parent.class,()->ParentMetadata.INSTANCE);"), cm);

        // The runtime sort switch resolves dot paths to flattened columns and carries no
        // EMBEDDED-container case.
        Files.writeString(sourceDir.resolve("Loc.java"), """
                package mmrel;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Loc {
                    String city;
                    Integer zip;
                    public String getCity() { return city; }
                    public void setCity(String city) { this.city = city; }
                    public Integer getZip() { return zip; }
                    public void setZip(Integer zip) { this.zip = zip; }
                }
                """);
        Files.writeString(sourceDir.resolve("Holder.java"), """
                package mmrel;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Holder {
                    @Identity Long id;
                    Loc loc;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Loc getLoc() { return loc; }
                    public void setLoc(Loc loc) { this.loc = loc; }
                }
                """);
        Files.writeString(sourceDir.resolve("Holders.java"), """
                package mmrel;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Holders extends CrudRepository<Holder, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Parent.java", "Child.java", "ParentRepository.java", "ChildRepository.java",
                "Loc.java", "Holder.java", "Holders.java");

        String impl = Files.readString(generatedDir.resolve("mmrel/HoldersImpl.java"));
        assertTrue(impl.contains("case \"loc.city\": return \"loc_city\";"),
                "embedded leaf path must resolve to its flattened column:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("case \"loc.zip\": return \"loc_zip\";"),
                "embedded leaf path must resolve to its flattened column:" + System.lineSeparator() + impl);
        assertTrue(!impl.replace(" ", "").contains("case\"loc\":"),
                "no container case for an EMBEDDED property:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("case \"id\": return \"id\";"),
                "the id case must remain:" + System.lineSeparator() + impl);
    }

    private void compile(Path sourceDir, Path classesDir, Path generatedDir, String... fileNames) throws Exception {
        compileWithDiagnostics(sourceDir, classesDir, generatedDir, fileNames);
    }

    /** The result of running the javac task: whole-round success plus every collected diagnostic. */
    private record TaskResult(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) { }

    private TaskResult runTask(Path sourceDir, Path classesDir, Path generatedDir, String... fileNames) throws Exception {
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
            return new TaskResult(success, diagnostics.getDiagnostics());
        }
    }

    /**
     * Runs the processor and compiles the generated sources (javac compiles annotation-round output in
     * later rounds unless -proc:only is set), asserting the whole task succeeded. Returns ALL collected
     * diagnostics so a test can also assert NOTE-level messages (e.g. the gate-skip note for a
     * non-reflection-free element-collection VO).
     */
    private List<Diagnostic<? extends JavaFileObject>> compileWithDiagnostics(
            Path sourceDir, Path classesDir, Path generatedDir, String... fileNames) throws Exception {
        TaskResult result = runTask(sourceDir, classesDir, generatedDir, fileNames);
        String messages = result.diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(Object::toString).collect(Collectors.joining(System.lineSeparator()));
        assertTrue(result.success(), "Annotation processing failed:" + System.lineSeparator() + messages);
        return result.diagnostics();
    }

    private static String notesOf(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.NOTE)
                .map(d -> d.getMessage(Locale.getDefault()))
                .collect(Collectors.joining(System.lineSeparator()));
    }
}
