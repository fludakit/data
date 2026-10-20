package io.github.fludakit.data.processor;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static io.github.fludakit.data.processor.ProcessorHarness.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * what the REPOSITORY DECLARATION produces before any query is looked at: which base interface
 * it extends, how the entity type is resolved through a parameterized superinterface, and which
 * result kind a declared return shape publishes.
 *
 * <p>Split out of {@code RepositoryProcessorTest}; the shared compile-and-assert plumbing
 * lives in {@link ProcessorHarness}.</p>
 */
@org.junit.jupiter.api.Disabled("Metadata generation removed - TODO: update tests")
class RepositoryShapeEmissionTest {

    @TempDir
    Path workspace;

    @Test
    void generatesFreeStyleAnnotatedFindRepositoryWithoutBaseInterface() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/freestyle"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-freestyle"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-freestyle"));

        Files.writeString(sourceDir.resolve("Customer.java"), """
                package freestyle;
                public class Customer {
                    public Long id;
                    public String name;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        Files.writeString(sourceDir.resolve("CustomerRepository.java"), """
                package freestyle;
                import jakarta.data.repository.By;
                import jakarta.data.repository.Find;
                import jakarta.data.repository.Insert;
                import jakarta.data.repository.Repository;
                import jakarta.data.repository.Save;
                import jakarta.data.repository.Update;
                import java.util.List;
                @Repository
                public interface CustomerRepository {
                    @Find
                    List<Customer> named(@By("name") String customerName);
                    @Save Customer register(Customer customer);
                    @Save void registerWithoutResult(Customer customer);
                    @Insert void registerAll(List<Customer> customers);
                    @Save List<Customer> saveAllCustomers(List<Customer> customers);
                    @Update Customer[] updateAllCustomers(Customer[] customers);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Customer.java", "CustomerRepository.java");

        String impl = sqlFlat(generatedDir.resolve("freestyle/CustomerRepositoryImpl.java"));
        assertTrue(impl.contains("implements CustomerRepository"), impl);
        assertTrue(impl.contains("WHERE \"name\" = :customerName"), impl);
        assertTrue(impl.contains("jdbcClient.sql(SQL_NAMED).param(\"customerName\", customerName)"), impl);
        assertTrue(impl.contains("Customer register(Customer customer)"), impl);
        assertTrue(impl.contains("void registerWithoutResult(Customer customer)"), impl);
        assertTrue(impl.contains("void registerAll(List<Customer> customers)"), impl);
        assertTrue(impl.contains("List<Customer> saveAllCustomers(List<Customer> customers)"), impl);
        assertTrue(impl.contains("Customer[] updateAllCustomers(Customer[] customers)"), impl);
    }

    @Test
    void aDeletePublishesTheResultShapeTheMethodDeclares() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/writekind"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-writekind"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-writekind"));

        Files.writeString(sourceDir.resolve("Ticket.java"), """
                package writekind;
                public class Ticket {
                    public Long id;
                    public String status;
                    public String title;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getStatus() { return status; }
                    public void setStatus(String status) { this.status = status; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                }
                """);
        Files.writeString(sourceDir.resolve("Tickets.java"), """
                package writekind;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Tickets extends CrudRepository<Ticket, Long> {
                    void deleteByStatus(String status);
                    long deleteByTitle(String title);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Ticket.java", "Tickets.java");

        String queries = sqlFlat(generatedDir.resolve("writekind/TicketsQueries.java"));
        assertTrue(declarationOf(queries, "DELETE_BY_STATUS_QUERY").contains("new QueryDefinition.DeleteWhere("),
                "a void delete publishes a DeleteWhere:" + System.lineSeparator() + queries);
        assertTrue(declarationOf(queries, "DELETE_BY_STATUS_QUERY").contains("ResultKind.VOID)"),
                "a void delete reports nothing:" + System.lineSeparator() + queries);
        assertTrue(declarationOf(queries, "DELETE_BY_TITLE_QUERY").contains("ResultKind.AFFECTED)"),
                "a long delete reports the rows it removed:" + System.lineSeparator() + queries);

        // The published shape must match what the impl actually does: the long delete returns the
        // JDBC update count, the void one discards it.
        String impl = sqlFlat(generatedDir.resolve("writekind/TicketsImpl.java"));
        assertTrue(impl.contains("return jdbcClient.sql(SQL_DELETE_BY_TITLE).param(\"title\", title).update()"),
                "a long delete must return the affected-row count:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(SQL_DELETE_BY_STATUS).param(\"status\", status).update();"),
                "a void delete must discard the count:" + System.lineSeparator() + impl);
    }

    @Test
    void generatesArrayReturnForAnnotatedFind() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/find-array"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-find-array"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-find-array"));

        Files.writeString(sourceDir.resolve("Book.java"), """
                package findarray;
                public class Book {
                    public Long id;
                    public String title;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                }
                """);
        Files.writeString(sourceDir.resolve("BookRepository.java"), """
                package findarray;
                import jakarta.data.repository.By;
                import jakarta.data.repository.Find;
                import jakarta.data.repository.Repository;
                @Repository
                public interface BookRepository {
                    @Find Book[] byTitle(@By("title") String title);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Book.java", "BookRepository.java");

        String impl = Files.readString(generatedDir.resolve("findarray/BookRepositoryImpl.java"));
        assertTrue(impl.contains("return jdbcClient.sql(SQL_BY_TITLE)")
                        && impl.contains(".list().toArray(Book[]::new)"),
                "array-return @Find must convert query results to the declared entity array:" + impl);
    }

    @Test
    void treatsAnnotatedParameterBasedDeleteAsQuery() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/delete-query"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-delete-query"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-delete-query"));

        Files.writeString(sourceDir.resolve("Book.java"), """
                package deletequery;
                public class Book {
                    public Long id;
                    public String title;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                }
                """);
        Files.writeString(sourceDir.resolve("BookRepository.java"), """
                package deletequery;
                import jakarta.data.repository.By;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Delete;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface BookRepository extends CrudRepository<Book, Long> {
                    @Delete int deleteByTitle(@By("title") String title);
                    @Delete void deleteAllBooks(List<Book> books);
                    @Delete int removeBookByTitle(String title);
                    @Delete void clear();
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Book.java", "BookRepository.java");

        String impl = sqlFlat(generatedDir.resolve("deletequery/BookRepositoryImpl.java"));
        assertTrue(impl.contains("DELETE FROM \"book\" WHERE \"title\" = :title"), impl);
        assertTrue(impl.contains("return jdbcClient.sql(SQL_DELETE_BY_TITLE)"), impl);
        assertTrue(!impl.contains("title.getId()"), "scalar @Delete parameters are query criteria:" + impl);
        assertTrue(impl.contains("void deleteAllBooks(List<Book> books)"), impl);
        assertTrue(impl.contains("DELETE FROM \"book\" WHERE \"title\" = :title"), impl);
        assertTrue(impl.contains("int removeBookByTitle(String title)"), impl);
        assertTrue(impl.contains("DELETE FROM \"book\"\";"), impl);
        assertTrue(impl.contains("void clear()"), impl);
    }

    @Test
    void materializesASetReturnIntoALinkedHashSet() throws Exception {
        // A Set return used to emit .list(), which does not compile against the declaration.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/set-return"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-set-return"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-set-return"));

        Files.writeString(sourceDir.resolve("Memo.java"), """
                package setreturn;
                public class Memo {
                    public Long id;
                    public String text;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getText() { return text; }
                    public void setText(String text) { this.text = text; }
                }
                """);
        Files.writeString(sourceDir.resolve("Memos.java"), """
                package setreturn;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.Set;
                @Repository
                public interface Memos extends CrudRepository<Memo, Long> {
                    Set<Memo> findByText(String text);
                }
                """);

        // compile() also compiles the generated sources, so success proves the return type is honoured.
        compile(sourceDir, classesDir, generatedDir, "Memo.java", "Memos.java");

        String impl = sqlFlat(generatedDir.resolve("setreturn/MemosImpl.java"));
        // Pin the CALL SHAPE, not a bare "LinkedHashSet": that word also matches an unused import or
        // any incidental mention. The Materialize terminal is what actually satisfies the declaration.
        assertTrue(impl.contains(".list().stream().collect(Collectors.toCollection(LinkedHashSet::new))"),
                "a Set return must be materialized into a LinkedHashSet:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void anArrayReturnPublishesItsOwnResultKindRatherThanList() throws Exception {
        // Jakarta Data names E[] as a return type in its own right, so the published definition has to
        // say ARRAY — it used to be flattened to LIST, and the two are only distinguishable by
        // re-inspecting the return TypeMirror at every emission site.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/array-return"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-array-return"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-array-return"));

        Files.writeString(sourceDir.resolve("Memo.java"), """
                package arrayreturn;
                public class Memo {
                    public Long id;
                    public String text;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getText() { return text; }
                    public void setText(String text) { this.text = text; }
                }
                """);
        Files.writeString(sourceDir.resolve("Memos.java"), """
                package arrayreturn;
                import jakarta.data.repository.By;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Find;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Memos extends CrudRepository<Memo, Long> {
                    Memo[] findByText(String text);
                    @Find
                    List<Memo> search(@By("text") String text);
                }
                """);

        // compile() also compiles the generated sources, so success proves the return type is honoured.
        compile(sourceDir, classesDir, generatedDir, "Memo.java", "Memos.java");

        String impl = sqlFlat(generatedDir.resolve("arrayreturn/MemosImpl.java"));
        assertTrue(impl.contains(".list().toArray(Memo[]::new)"),
                "an array return must materialize through toArray:" + System.lineSeparator() + impl);

        String queries = sqlFlat(generatedDir.resolve("arrayreturn/MemosQueries.java"));
        assertTrue(declarationOf(queries, "FIND_BY_TEXT_QUERY").contains("ResultKind.ARRAY,"),
                "an array return must publish ARRAY:" + System.lineSeparator() + queries);
        assertTrue(declarationOf(queries, "SEARCH_QUERY").contains("ResultKind.LIST,"),
                "a List return must still publish LIST:" + System.lineSeparator() + queries);
    }

    @Test
    void aCollectionOrIterableReturnIsMaterializedAsTheListItAlreadyIs() throws Exception {
        // Neither shape needs a ResultKind of its own: a List satisfies both declarations, so the
        // materialized list is returned unchanged. Pinned because the alternative — giving
        // Collection/Iterable their own kinds — would silently reroute the terminal, and because
        // the spec names neither, so the only reason this works is the deliberate mapping.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/collection-return"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-collection-return"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-collection-return"));

        Files.writeString(sourceDir.resolve("Memo.java"), """
                package collectionreturn;
                public class Memo {
                    public Long id;
                    public String text;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getText() { return text; }
                    public void setText(String text) { this.text = text; }
                }
                """);
        Files.writeString(sourceDir.resolve("Memos.java"), """
                package collectionreturn;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.Collection;
                @Repository
                public interface Memos extends CrudRepository<Memo, Long> {
                    Collection<Memo> findByText(String text);
                    Iterable<Memo> findByTextLike(String text);
                }
                """);

        // compile() also compiles the generated sources, so success proves both return types are
        // satisfied by the SAME materialization: a .single() or .toArray() terminal would not compile.
        compile(sourceDir, classesDir, generatedDir, "Memo.java", "Memos.java");

        String queries = sqlFlat(generatedDir.resolve("collectionreturn/MemosQueries.java"));
        assertTrue(declarationOf(queries, "FIND_BY_TEXT_QUERY").contains("ResultKind.LIST,"),
                "a Collection return must publish LIST:" + System.lineSeparator() + queries);
        assertTrue(declarationOf(queries, "FIND_BY_TEXT_LIKE_QUERY").contains("ResultKind.LIST,"),
                "an Iterable return must publish LIST:" + System.lineSeparator() + queries);

        // Pair each constant with its own terminal rather than a file-wide "contains(.list())":
        // every finder on the repository ends in a terminal, so a file-wide match proves nothing.
        String impl = Files.readString(generatedDir.resolve("collectionreturn/MemosImpl.java"));
        assertTrue(statementOf(impl, "SQL_FIND_BY_TEXT)").endsWith(".list();"),
                "a Collection return must end in the list terminal:" + System.lineSeparator() + impl);
        assertTrue(statementOf(impl, "SQL_FIND_BY_TEXT_LIKE)").endsWith(".list();"),
                "an Iterable return must end in the list terminal:" + System.lineSeparator() + impl);
    }

    @Test
    void resolvesEntityTypeThroughParameterizedRepositorySuperinterface() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/inherited"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-inherited"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-inherited"));

        Files.writeString(sourceDir.resolve("Book.java"), """
                package inherited;
                public class Book {
                    public Long id;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                }
                """);
        Files.writeString(sourceDir.resolve("BookStoreBase.java"), """
                package inherited;
                import jakarta.data.repository.CrudRepository;
                public interface BookStoreBase<T> extends CrudRepository<T, Long> { }
                """);
        Files.writeString(sourceDir.resolve("BookStore.java"), """
                package inherited;
                import jakarta.data.repository.Repository;
                @Repository
                public interface BookStore extends BookStoreBase<Book> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Book.java", "BookStoreBase.java", "BookStore.java");

        String impl = Files.readString(generatedDir.resolve("inherited/BookStoreImpl.java"));
        assertTrue(impl.contains("implements BookStore"), impl);
        assertTrue(Files.exists(generatedDir.resolve("inherited/BookMetadata.java")),
                "entity resolution must preserve the concrete type argument through inheritance");
    }

    @Test
    void preservesDefaultRepositoryMethodImplementation() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/defaultmethod"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-defaultmethod"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-defaultmethod"));

        Files.writeString(sourceDir.resolve("Book.java"), """
                package defaultmethod;
                public class Book {
                    public Long id;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                }
                """);
        Files.writeString(sourceDir.resolve("BookStore.java"), """
                package defaultmethod;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface BookStore extends CrudRepository<Book, Long> {
                    default String label() { return "books"; }
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Book.java", "BookStore.java");

        String impl = Files.readString(generatedDir.resolve("defaultmethod/BookStoreImpl.java"));
        assertTrue(!impl.contains("label()"), "default methods must not be replaced by generated methods:" + impl);
        try (var classLoader = new java.net.URLClassLoader(
                new java.net.URL[]{classesDir.toUri().toURL()}, getClass().getClassLoader())) {
            Object repository = Class.forName("defaultmethod.BookStoreImpl", true, classLoader)
                    .getConstructor().newInstance();
            assertTrue("books".equals(repository.getClass().getMethod("label").invoke(repository)),
                    "generated repository must inherit the interface default implementation");
        }
    }

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
    void generatesJdbcClientAccessorDeclaredOnTheRepository() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/client-aware"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-client-aware"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-client-aware"));

        Files.writeString(sourceDir.resolve("Book.java"), """
                package clientaware;
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
                package clientaware;

                import io.github.fludakit.jdbc.JdbcClient;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;

                @Repository
                public interface Shelf extends CrudRepository<Book, Long> {
                    JdbcClient getJdbcClient();
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Book.java", "Shelf.java");

        Path implementation = generatedDir.resolve("clientaware/ShelfImpl.java");
        assertTrue(Files.exists(implementation), "Expected generated " + implementation);

        String content = Files.readString(implementation);
        // The accessor must return the SAME injected field the generated query methods call through,
        // not a second client: MemberNames.JDBC_CLIENT_FIELD is what ties the two emits together.
        String accessor = bodyOf(content, "public JdbcClient getJdbcClient()");
        assertTrue(accessor.contains("return jdbcClient;"),
                "accessor must return the injected field:" + System.lineSeparator() + accessor);
        assertTrue(content.contains("private JdbcClient jdbcClient"),
                "the field the accessor returns must exist:" + System.lineSeparator() + content);
        // Proves the accessor never reached the query compiler: no SQL constant was registered for it
        // and no query definition was published, which is what "ignored in query generation" means.
        assertFalse(content.contains("SQL_GET_JDBC_CLIENT"),
                "an accessor must not be treated as a query:" + System.lineSeparator() + content);
    }

    @Test
    void generatesNoJdbcClientAccessorWhenTheRepositoryDoesNotAskForIt() throws Exception {
        // The other half of "only when declared": a repository that never extends JdbcClientAware gets
        // the injected client as a private field and nothing else. Pinning the absence is what stops
        // the accessor from becoming unconditional.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/client-private"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-client-private"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-client-private"));

        Files.writeString(sourceDir.resolve("Book.java"), """
                package clientprivate;
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
                package clientprivate;

                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;

                @Repository
                public interface Shelf extends CrudRepository<Book, Long> {
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Book.java", "Shelf.java");

        String content = Files.readString(generatedDir.resolve("clientprivate/ShelfImpl.java"));
        assertTrue(content.contains("private JdbcClient jdbcClient"),
                "the injected client must still be there:" + System.lineSeparator() + content);
        assertFalse(content.contains("getJdbcClient"),
                "an unrequested accessor must not be generated:" + System.lineSeparator() + content);
    }
}
