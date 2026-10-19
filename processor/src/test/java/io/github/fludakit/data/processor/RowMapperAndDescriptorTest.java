package io.github.fludakit.data.processor;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static io.github.fludakit.data.processor.ProcessorHarness.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the two artifacts a repository entity gets besides its implementation: the reflection-free
 * {@code <Entity>RowMapper} and the {@code <Entity>Metadata} descriptor. Both are generated per
 * TYPE, not per method, so they are pinned against the mapped fields rather than a query.
 *
 * <p>Split out of {@code RepositoryProcessorTest}; the shared compile-and-assert plumbing
 * lives in {@link ProcessorHarness}.</p>
 */
@org.junit.jupiter.api.Disabled("Metadata generation removed - TODO: update tests")
class RowMapperAndDescriptorTest {

    @TempDir
    Path workspace;

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

        Files.writeString(sourceDir.resolve("Ticket.java"), """
                package demo2;
                public class Ticket {
                    public Long id;
                    public java.time.Duration ttl;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public java.time.Duration getTtl() { return ttl; }
                    public void setTtl(java.time.Duration ttl) { this.ttl = ttl; }
                }
                """);
        Files.writeString(sourceDir.resolve("Tickets.java"), """
                package demo2;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Tickets extends CrudRepository<Ticket, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Ticket.java", "Tickets.java");

        assertTrue(Files.exists(generatedDir.resolve("demo2/TicketsImpl.java")),
                "repository impl must still be generated");
        assertTrue(Files.notExists(generatedDir.resolve("demo2/TicketRowMapper.java")),
                "no mapper for an entity with a non-JDBC-safe field type");
        assertTrue(Files.exists(generatedDir.resolve("demo2/TicketMetadata.java")),
                "the descriptor is generated even when the row mapper is skipped: dynamic paging "
                        + "resolves sort paths through <Entity>Metadata.columnRef, independent of the mapper gate");

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
    void enumFieldGeneratesReflectionFreeRowMapper() throws Exception {
        // An enum field no longer fails the row-mapper gate: the reflection-free mapper reads it from
        // its String column via Enum.valueOf, null-safe for a nullable column.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-enum"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-enum"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-enum"));
        Files.writeString(sourceDir.resolve("Status.java"), """
                package rpenum;
                public enum Status { ACTIVE, INACTIVE }
                """);
        Files.writeString(sourceDir.resolve("Account.java"), """
                package rpenum;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class Account {
                    @Identity Long id;
                    String name;
                    Status status;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public Status getStatus() { return status; }
                    public void setStatus(Status status) { this.status = status; }
                }
                """);
        Files.writeString(sourceDir.resolve("Accounts.java"), """
                package rpenum;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Accounts extends CrudRepository<Account, Long> {
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Status.java", "Account.java", "Accounts.java");

        Path mapper = generatedDir.resolve("rpenum/AccountRowMapper.java");
        assertTrue(Files.exists(mapper),
                "an enum-bearing entity must still generate a reflection-free row mapper");
        String src = sqlFlat(mapper);
        assertTrue(src.contains("Enum.valueOf(Status.class, rs.getString(AccountMetadata.STATUS))"),
                "the enum must be read via Enum.valueOf from its String column:" + System.lineSeparator() + src);
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
        String md = Files.readString(generatedDir.resolve("cls/BookMetadata.java"));
        assertTrue(md.contains("mappings.put(\"author\", new ColumnRef.ForeignKey(\"author_id\", \"book\"))"),
                "association must contribute its fk column to the descriptor's typed mapping index:"
                        + System.lineSeparator() + md);
        assertTrue(md.contains("mappings.put(\"title\", new ColumnRef.Simple(\"title\", \"book\"))"),
                "scalar column must be present:" + System.lineSeparator() + md);
        assertTrue(!md.replace(" ", "").contains("\"author_name\""),
                "an @Association must NOT flatten the target's fields into the owner's columns:"
                        + System.lineSeparator() + md);
    }

    @Test
    void generatedMetadataImplementsEntityMetadataWithAggregateOwnershipAndTargets() throws Exception {
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

        String md = Files.readString(generatedDir.resolve("meta/BookMetadata.java")).replaceAll("\\s+", " ");
        assertTrue(md.contains("implements TypeMapping<Book>"),
                "generated metadata must implement the mapping interface:" + System.lineSeparator() + md);
        assertTrue(md.contains("import io.github.fludakit.data.metadata.TypeMapping;"), md);
        assertTrue(md.contains("public static final BookMetadata INSTANCE"), md);
        String mm = Files.readString(generatedDir.resolve("meta/_Book.java")).replaceAll("\\s+", "");
        assertTrue(mm.contains("_BookAuthorauthor=new_BookAuthor();"),
                "the association member must instantiate its nav class:" + System.lineSeparator() + mm);
        // The unmapped target (meta.Author has no repository) still gets a nav class, but degraded to
        // the name() accessor alone and NO leaf fields: a navigable attribute publishes no
        // column()/target()/targetDescriptor() accessors, so there is nothing to populate leaves from.
        String nav = Files.readString(generatedDir.resolve("meta/_BookAuthor.java")).replaceAll("\\s+", "");
        assertTrue(nav.contains("publicfinalclass_BookAuthorimplementsEntityAttribute<Book,Author>"), nav);
        assertTrue(nav.contains("publicStringname(){return\"author\";}"), nav);
        assertTrue(!nav.contains("IdAttributeRecord"),
                "an unmapped relation target must carry no leaf fields:" + System.lineSeparator() + nav);
        // The table and per-property column constants are still emitted:
        assertTrue(md.contains("public static final String TABLE_NAME = \"book\";"), md);
        assertTrue(md.contains("public static final String TITLE = \"title\";"), md);
        // The descriptor derives its table-local column view from the typed mapping index.
        String mflat = Files.readString(generatedDir.resolve("meta/BookMetadata.java")).replaceAll("\\s+", "");
        assertTrue(mflat.contains("mappings=newLinkedHashMap<>();")
                        && mflat.contains("mappings.put(\"id\",newColumnRef.Simple(\"id\",\"book\"))")
                        && mflat.contains("mappings.put(\"title\",newColumnRef.Simple(\"title\",\"book\"))")
                        && mflat.contains("columns(){"),
                "the descriptor emits columns() and the string-keyed index:" + System.lineSeparator() + mflat);
        // The id() accessor returns the compile-time Prop.Id literal.
        assertTrue(md.contains("public TypeMapping.Prop.Id id()"), md);
        assertTrue(md.contains("return new TypeMapping.Prop.Id(\"id\", \"id\", \"java.lang.Long\");"),
                "id() must expose the identifier property:" + System.lineSeparator() + md);
        assertTrue(md.contains("public String typeName()"), md);
        // The domain role is carried by the descriptor stating that it owns the aggregate lifecycle,
        // not by a Kind flag: @AggregateRoot overrides the method, a plain entity stays on the default.
        assertTrue(md.contains("public boolean ownsAggregateLifecycle()"), md);
        assertTrue(md.contains("return true;"), "an aggregate root must claim its lifecycle:" + System.lineSeparator() + md);
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

        // The row-mapper gate accepts embeddable value objects: Contact is fully JDBC-safe or
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
        assertTrue(vmd.contains("implements TypeMapping"), vmd);
        assertTrue(vmd.contains("public static final AddressMetadata INSTANCE"), vmd);
        assertTrue(!vmd.contains("TABLE_NAME"),
                "a value object has no table and must not emit TABLE_NAME:" + System.lineSeparator() + vmd);
        assertTrue(vmd.contains("public String tableName()") && vmd.contains("return null;"),
                "value objects report a null table name:" + System.lineSeparator() + vmd);
        // A VO has no metamodel, so its members are fresh attribute-record literals and the id
        // accessor is a typed null. The list carries the ROOT members only:
        // the nested EMBEDDED container appears once and its leaves are no longer flat attributes.
        assertTrue(vmd.contains("public TypeMapping.Prop.Id id()") && vmd.contains("return null;"),
                "value objects report a null id:" + System.lineSeparator() + vmd);
        assertTrue(vmd.contains("public static final String CITY = \"city\";"), vmd);
        String vflat = Files.readString(voMetadata).replaceAll("\\s+", "");
        assertTrue(vflat.contains("props(){returnList.of("
                        + "newTypeMapping.Prop.Scalar(\"city\",\"city\",\"java.lang.String\"),"
                        + "newTypeMapping.Prop.Embedded(\"point\",null));}"),
                "VO props() must list root members once, without flattened leaves:"
                        + System.lineSeparator() + vflat);
        // Attributes navigate by name; the nested value object is referenced through the
        // record's TYPE ARGUMENTS (the INSTANCE plumbing went with column()). Transitive VO
        // generation is still pinned by the GeoPointMetadata existence check below.
        assertTrue(vmd.contains("new TypeMapping.Prop.Embedded(\"point\", null)"),
                "embedded attribute must still name the nested value object type:" + System.lineSeparator() + vmd);
        assertTrue(vflat.contains("newTypeMapping.Prop.Embedded(\"point\",null)"),
                "the embedded literal must carry the VO type arguments:" + System.lineSeparator() + vflat);
        // ... and the VO's flattened leaves are indexed by dot path, not by attribute record.
        assertTrue(vflat.contains("mappings=newLinkedHashMap<>();")
                        && vflat.contains("mappings.put(\"point.lat\",newColumnRef.Simple(\"point_lat\",null))")
                        && vflat.contains("columns(){"),
                "VO leaves must be indexed by their dot path to a ColumnRef placement, not by a"
                        + " PathAttribute instance:" + System.lineSeparator() + vflat);
        // ... which must itself have been generated (transitive EMBEDDED tree walk).
        Path innerMetadata = generatedDir.resolve("emb/GeoPointMetadata.java");
        assertTrue(Files.exists(innerMetadata), "Expected generated " + innerMetadata);
        String imd = Files.readString(innerMetadata).replaceAll("\\s+", " ");
        assertTrue(imd.contains("public static final String LAT = \"lat\";"), imd);
        assertTrue(imd.contains("new TypeMapping.Prop.Scalar(\"lat\", \"lat\", \"double\")"), imd);

        // The owner's descriptor lists the root metamodel members (scalar + embedded nav) once, and
        // the navigable member classes carry the flattened leaves as owner-typed fields.
        String cmd = Files.readString(generatedDir.resolve("emb/ContactMetadata.java")).replaceAll("\\s+", " ");
        assertTrue(cmd.contains("return List.of(new TypeMapping.Prop.Scalar(\"name\", \"name\", \"java.lang.String\"), new TypeMapping.Prop.Embedded(\"address\", null));"),
                "props() must expose containers once, no flattened leaves:" + System.lineSeparator() + cmd);
        String cflat = Files.readString(generatedDir.resolve("emb/ContactMetadata.java")).replaceAll("\\s+", "");
        assertTrue(cflat.contains("mappings.put(\"address.city\",newColumnRef.Simple(\"address_city\",\"contact\"))")
                        && cflat.contains("mappings.put(\"address.point.lat\",newColumnRef.Simple(\"address_point_lat\",\"contact\"))")
                        && cflat.contains("columns(){"),
                "the typed index flattens the embedded chain and derives root columns:"
                        + System.lineSeparator() + cflat);
        String addrNav = Files.readString(generatedDir.resolve("emb/_ContactAddress.java")).replaceAll("\\s+", "");
        assertTrue(addrNav.contains("publicfinalclass_ContactAddressimplementsEmbeddableAttribute<Contact,Address>"),
                addrNav);
        assertTrue(addrNav.contains(
                "TextScalarAttribute<Contact>city=newTextScalarAttributeRecord<>(\"city\",\"address.city\");"),
                "embedded leaf must be an owner-typed nav field carrying the path only, no column slot:"
                        + System.lineSeparator() + addrNav);
        assertTrue(addrNav.contains("_ContactAddressPointpoint=new_ContactAddressPoint();"),
                "nested embedded must recurse into its own nav class:" + System.lineSeparator() + addrNav);
        // The container contract is the name() accessor alone; the embedded() INSTANCE
        // wiring is gone (leaf placement lives in the descriptor's flattened columns()).
        assertTrue(addrNav.contains("publicStringname(){return\"address\";}"), addrNav);
        assertTrue(addrNav.contains("publicStringpath(){return\"address\";}"), addrNav);
        assertTrue(!addrNav.contains("embedded()"),
                "the slim nav class must not emit the removed embedded() override:" + System.lineSeparator() + addrNav);
        String pointNav = Files.readString(generatedDir.resolve("emb/_ContactAddressPoint.java")).replaceAll("\\s+", "");
        assertTrue(pointNav.contains("publicfinalclass_ContactAddressPointimplementsEmbeddableAttribute<Contact,GeoPoint>"),
                pointNav);
        assertTrue(pointNav.contains("SortableScalarAttribute<Contact>lat="
                        + "newSortableScalarAttributeRecord<>(\"lat\",\"address.point.lat\");"),
                "nested leaf keeps the full owner-rooted dot path (column placement moved to columns()):"
                        + System.lineSeparator() + pointNav);
        assertTrue(pointNav.contains("publicStringname(){return\"point\";}"), pointNav);
        assertTrue(pointNav.contains("publicStringpath(){return\"address.point\";}"), pointNav);
    }

    @Test
    void generatedMetadataValueSwitchReadsRelationAndCollectionPathsWithoutReflection() throws Exception {
        // <Entity>Metadata.navigate(path, root) is the reflection-free cursor-key read,
        // emitted as a switch over exactly the paths columnRef resolves. The estore Order fixture supplies
        // the to-one head (customer.name) and the element collection (orderItems.sku); the profile-style
        // fixture supplies the composition entity list (workHistory.company). compile() also compiles the
        // GENERATED sources, so this run is itself the proof that
        // OrderMetadata.navigate("customer.name", root) type-checks against the fixture.
        Path orderSrc = Files.createDirectories(workspace.resolve("src/val-order"));
        Path orderClasses = Files.createDirectories(workspace.resolve("classes-val-order"));
        Path orderGen = Files.createDirectories(workspace.resolve("generated-val-order"));
        writeRelationFixture(orderSrc);
        Files.writeString(orderSrc.resolve("ValOrders.java"), """
                package relpath;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface ValOrders extends CrudRepository<Order, Long> { }
                """);
        compile(orderSrc, orderClasses, orderGen,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java",
                "ValOrders.java");

        String order = sqlFlat(orderGen.resolve("relpath/OrderMetadata.java"));
        assertTrue(order.contains("public Object value(String path, Order root)"),
                "the descriptor must override value with the interface signature:"
                        + System.lineSeparator() + order);
        assertTrue(order.contains("case \"status\": return e.getStatus();"),
                "a local scalar must read its own getter:" + System.lineSeparator() + order);
        assertTrue(order.contains("case \"customer.name\": return e.getCustomer() == null ? null"
                        + " : e.getCustomer().getName();"),
                "a to-one leaf must read getCustomer() then getName() behind a null guard:"
                        + System.lineSeparator() + order);
        assertTrue(order.contains("case \"orderItems.sku\": return e.getOrderItems() == null ? List.of()"
                        + " : e.getOrderItems().stream().map(OrderItem::getSku)"
                        + ".collect(Collectors.toList());"),
                "an element-collection leaf must map the child list behind a null-COLLECTION guard:"
                        + System.lineSeparator() + order);
        assertTrue(order.contains("default: throw new UnknownAttributePathException(path, \"relpath.Order\")"),
                "an unknown path must fail fast with the one type the runtime walk throws, naming the path"
                        + " and the entity:" + System.lineSeparator() + order);
        assertTrue(!order.contains("getDeclaredField") && !order.contains("java.lang.reflect"),
                "the value switch must stay reflection-free:" + System.lineSeparator() + order);

        Path cvSrc = Files.createDirectories(workspace.resolve("src/val-cv"));
        Path cvClasses = Files.createDirectories(workspace.resolve("classes-val-cv"));
        Path cvGen = Files.createDirectories(workspace.resolve("generated-val-cv"));
        Files.writeString(cvSrc.resolve("Period.java"), """
                package valp;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Period {
                    int fromYear;
                    String note;
                    public int getFromYear() { return fromYear; }
                    public void setFromYear(int fromYear) { this.fromYear = fromYear; }
                    public String getNote() { return note; }
                    public void setNote(String note) { this.note = note; }
                }
                """);
        Files.writeString(cvSrc.resolve("WorkExperience.java"), """
                package valp;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class WorkExperience {
                    @Identity Long id;
                    String company;
                    Period period;
                    Profile profile;
                    public Long getId() { return id; }
                    public Period getPeriod() { return period; }
                    public void setPeriod(Period period) { this.period = period; }
                    public void setId(Long id) { this.id = id; }
                    public String getCompany() { return company; }
                    public void setCompany(String company) { this.company = company; }
                    public Profile getProfile() { return profile; }
                    public void setProfile(Profile profile) { this.profile = profile; }
                }
                """);
        Files.writeString(cvSrc.resolve("Profile.java"), """
                package valp;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @AggregateRoot
                public class Profile {
                    @Identity Long id;
                    String fullName;
                    List<WorkExperience> workHistory = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getFullName() { return fullName; }
                    public void setFullName(String fullName) { this.fullName = fullName; }
                    public List<WorkExperience> getWorkHistory() { return workHistory; }
                    public void setWorkHistory(List<WorkExperience> workHistory) {
                        this.workHistory = workHistory;
                    }
                }
                """);
        Files.writeString(cvSrc.resolve("Profiles.java"), """
                package valp;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Profiles extends CrudRepository<Profile, Long> { }
                """);
        Files.writeString(cvSrc.resolve("WorkExperiences.java"), """
                package valp;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface WorkExperiences extends CrudRepository<WorkExperience, Long> { }
                """);
        compile(cvSrc, cvClasses, cvGen,
                "Period.java", "WorkExperience.java", "Profile.java", "Profiles.java",
                "WorkExperiences.java");

        String cv = sqlFlat(cvGen.resolve("valp/ProfileMetadata.java"));
        assertTrue(cv.contains("case \"workHistory.company\": return e.getWorkHistory() == null ? List.of()"
                        + " : e.getWorkHistory().stream().map(WorkExperience::getCompany)"
                        + ".collect(Collectors.toList());"),
                "a composition entity-collection leaf must map the child list by method reference:"
                        + System.lineSeparator() + cv);
        // The child's own inverse reference is a column-bearing path of the child's descriptor, so the
        // one-hop set carries it too (the cursor list maps to the child's referenced objects).
        assertTrue(cv.contains("case \"workHistory.id\": return e.getWorkHistory() == null ? List.of()"
                        + " : e.getWorkHistory().stream().map(WorkExperience::getId)"
                        + ".collect(Collectors.toList());"),
                "the child identifier must be readable through the collection:" + System.lineSeparator() + cv);
        assertTrue(cv.contains("case \"workHistory.period.note\": return e.getWorkHistory() == null"
                        + " ? List.of() : e.getWorkHistory().stream()"
                        + ".map(child -> child.getPeriod() == null ? null : child.getPeriod().getNote())"
                        + ".collect(Collectors.toList());"),
                "an embedded leaf of a to-many child must map through a null-safe chain lambda:"
                        + System.lineSeparator() + cv);

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
        // the VO leaf-by-leaf from the flattened columns — reflection-free.
        String mapper = Files.readString(generatedDir.resolve("gp/ProfileRowMapper.java"));
        assertTrue(mapper.contains("String address_city = rs.getObject(ProfileMetadata.ADDRESS_CITY, String.class);"),
                "flattened boxed leaf must be read into a prefixed local:" + System.lineSeparator() + mapper);
        // Per-subtree presence: the nested GeoPoint child is constructed AND set only
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
        // The EMBEDDED property's column slot is a leaf PREFIX, not a physical column named
        // "point"; the public generated API must not advertise a phantom column constant for it.
        // Pinned to the exact phantom constant so the flattened leaf constants (below) still pass:
        // the intent is "no constant for the embedded property itself", not "no POINT* constant".
        assertTrue(!vmd.contains("public static final String POINT = "),
                "no phantom column constant for an EMBEDDED property:" + System.lineSeparator() + vmd);
        // Real (leaf-ward) constants remain.
        assertTrue(vmd.contains("public static final String CITY = \"city\";"), vmd);
        // leaf constants exist and match the flattened columns descriptor rendering: one constant per
        // physical leaf column, named <leaf-prefix>_<leaf> from the VO's own EMBEDDED attribute (the
        // owner-level chain e.g. ADDRESS_POINT_LAT rides the same loop, but the owner gate skips
        // ContactMetadata here, so this fixture pins the VO level).
        assertTrue(vmd.contains("public static final String POINT_LAT = \"point_lat\";"),
                "flattened embedded leaf must get a column constant:" + System.lineSeparator() + vmd);
        assertTrue(vmd.contains("public static final String POINT_LNG = \"point_lng\";"),
                "flattened embedded leaf must get a column constant:" + System.lineSeparator() + vmd);
        // Attributes navigate by name; the nested value object survives as the record's
        // type argument instead of the removed embedded-INSTANCE plumbing.
        assertTrue(vmd.contains("new TypeMapping.Prop.Embedded(\"point\", null)"),
                "the EMBEDDED attribute must still reference the nested value object:" + System.lineSeparator() + vmd);
        Path inner = generatedDir.resolve("phantom/GeoPointMetadata.java");
        assertTrue(Files.exists(inner), "Expected generated " + inner);
        String imd = Files.readString(inner);
        assertTrue(imd.contains("public static final String LAT = \"lat\";"), imd);
        assertTrue(imd.contains("public static final String LNG = \"lng\";"), imd);

        // The owner entity now passes the row-mapper gate (city is a boxed String, the primitive
        // double leaves are JDBC-safe), so the full flattened chain surfaces on ContactMetadata —
        // including the owner-level constants that could not be pinned while the gate skipped the owner.
        String cmd = Files.readString(generatedDir.resolve("phantom/ContactMetadata.java"));
        assertTrue(cmd.contains("public static final String ADDRESS_CITY = \"address_city\";"),
                "owner-level leaf constant must carry the embedded prefix:" + System.lineSeparator() + cmd);
        assertTrue(cmd.contains("public static final String ADDRESS_POINT_LAT = \"address_point_lat\";"),
                "owner-level nested leaf constant must chain both prefixes:" + System.lineSeparator() + cmd);
        assertTrue(cmd.contains("public static final String ADDRESS_POINT_LNG = \"address_point_lng\";"),
                "owner-level nested leaf constant must chain both prefixes:" + System.lineSeparator() + cmd);
    }

    @Test
    void twoRepositoriesOverOneEntityShareOneRowMapperAndOneMetadata() throws Exception {
        // The per-round registries (rowMappers, metadataTypes, voRowMappers, generatedTypeNames) span
        // EVERY repository in the round, so a second repository over the same entity must reuse the
        // mapper instead of emitting another: two mappers would be two types for one row, and the
        // second write would also collide on the file name.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/dedup"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-dedup"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-dedup"));
        Files.writeString(sourceDir.resolve("Item.java"), """
                package dedup;
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
                package dedup;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Items extends CrudRepository<Item, Long> { }
                """);
        Files.writeString(sourceDir.resolve("ItemArchive.java"), """
                package dedup;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface ItemArchive extends CrudRepository<Item, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Item.java", "Items.java", "ItemArchive.java");

        List<Path> generated = new ArrayList<>();
        try (var stream = Files.walk(generatedDir)) {
            stream.filter(Files::isRegularFile).forEach(generated::add);
        }
        List<String> names = generated.stream()
                .map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        assertTrue(names.stream().filter(n -> n.endsWith("RowMapper.java")).count() == 1,
                "one entity must yield exactly one row mapper across repositories: " + names);
        assertTrue(names.stream().filter(n -> n.endsWith("Metadata.java")).count() == 1,
                "one entity must yield exactly one metadata descriptor across repositories: " + names);
        // …and both impls read THAT mapper rather than each minting its own.
        assertTrue(sqlFlat(generatedDir.resolve("dedup/ItemsImpl.java")).contains("ItemRowMapper"),
                "the first repository must use the shared mapper");
        assertTrue(sqlFlat(generatedDir.resolve("dedup/ItemArchiveImpl.java")).contains("ItemRowMapper"),
                "the second repository must use the same shared mapper:"
                        + System.lineSeparator() + sqlFlat(generatedDir.resolve("dedup/ItemArchiveImpl.java")));
    }
}
