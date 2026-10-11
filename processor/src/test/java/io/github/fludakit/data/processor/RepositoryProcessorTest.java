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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryProcessorTest {

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

    /**
     * Characterization: the LIKE-family operators all render the SAME SQL text, so the SQL pin alone
     * cannot tell them apart — only the `%` wrapping distinguishes CONTAINS from STARTS_WITH from
     * ENDS_WITH. Before this test nothing exercised these operators through the processor at all
     * (grep for "Contains"/"StartsWith"/"EndsWith" returned zero hits), so a swap between, say,
     * STARTS_WITH and ENDS_WITH would have produced identical compiled SQL and identical tests.
     */
    @Test
    void pinsStringOperatorSqlAndPercentWrapping() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/strop"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-strop"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-strop"));

        Files.writeString(sourceDir.resolve("Note.java"), """
                package strop;
                public class Note {
                    public Long id;
                    public String text;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getText() { return text; }
                    public void setText(String text) { this.text = text; }
                }
                """);
        Files.writeString(sourceDir.resolve("Notes.java"), """
                package strop;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Notes extends CrudRepository<Note, Long> {
                    List<Note> findByTextContains(String text);
                    List<Note> findByTextStartsWith(String text);
                    List<Note> findByTextEndsWith(String text);
                    List<Note> findByTextNotContains(String text);
                    List<Note> findByTextNotStartsWith(String text);
                    List<Note> findByTextNotEndsWith(String text);
                    List<Note> findByTextLike(String text);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Note.java", "Notes.java");

        String impl = sqlFlat(generatedDir.resolve("strop/NotesImpl.java"));

        // Every LIKE-family operator renders the same PREDICATE text ("... LIKE :text"), so pinning the
        // SQL string alone cannot tell them apart. Pair each constant with its own binding line: that
        // pairing is the only thing that distinguishes a correct CONTAINS from a swapped STARTS_WITH.
        assertPinned(impl, "SQL_FIND_BY_TEXT_CONTAINS", "\"%\" + text + \"%\"");
        assertPinned(impl, "SQL_FIND_BY_TEXT_STARTS_WITH", "text + \"%\"");
        assertPinned(impl, "SQL_FIND_BY_TEXT_ENDS_WITH", "\"%\" + text");
        assertPinned(impl, "SQL_FIND_BY_TEXT_LIKE", "text");
        // The negated forms must carry the same wrapping — they are separate switch arms in
        // boundValue, and a forgotten arm would bind the bare value and silently match nothing.
        assertPinned(impl, "SQL_FIND_BY_TEXT_NOT_CONTAINS", "\"%\" + text + \"%\"");
        assertPinned(impl, "SQL_FIND_BY_TEXT_NOT_STARTS_WITH", "text + \"%\"");
        assertPinned(impl, "SQL_FIND_BY_TEXT_NOT_ENDS_WITH", "\"%\" + text");

        // ...and NOT LIKE must actually be rendered for the negated constants, not LIKE. sqlFlat strips
        // the generated source's \" escapes, so the expected text carries plain quotes.
        for (String negated : new String[]{"NOT_CONTAINS", "NOT_STARTS_WITH", "NOT_ENDS_WITH"}) {
            assertTrue(impl.contains("SQL_FIND_BY_TEXT_" + negated + " = \"SELECT \"id\", \"text\""
                            + " FROM \"note\" WHERE \"text\" NOT LIKE :text\""),
                    negated + " must render NOT LIKE:" + System.lineSeparator() + impl);
        }
    }

    /**
     * Pins that the method using {@code constant} binds its parameter with exactly {@code binding}.
     * Asserting the two together is what stops a same-SQL operator from passing on the wrong binding.
     */
    private static void assertPinned(String impl, String constant, String binding) {
        assertTrue(impl.contains("sql(" + constant + ").param(\"text\", " + binding + ")"),
                constant + " must bind `" + binding + "`:" + System.lineSeparator() + impl);
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
    void rejectsPredicatePropertyThatDoesNotResolve() throws Exception {
        // The bare fallback used to snake-case the property into a column with no diagnostic, so a
        // typo produced SQL against a column that does not exist.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/unresolved-path"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-unresolved-path"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-unresolved-path"));

        Files.writeString(sourceDir.resolve("Memo.java"), """
                package unresolvedpath;
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
                package unresolvedpath;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Memos extends CrudRepository<Memo, Long> {
                    List<Memo> findByBogusField(String value);
                }
                """);

        TaskResult result = runTask(sourceDir, classesDir, generatedDir, "Memo.java", "Memos.java");

        assertFalse(result.success(), "an unresolvable method-name property must be rejected");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                        && d.getMessage(Locale.ROOT).contains("does not resolve on entity")),
                "expected the unresolved-property diagnostic:" + System.lineSeparator() + result.diagnostics());
    }

    @Test
    void rejectsNonSpecKeywordSuffixThatLexesIntoThePropertyName() throws Exception {
        // `IsNull` is not a Jakarta Data keyword: it lexes as property `nameIs` plus the Null operator.
        // The strict resolution check is the net that catches this mis-spelling.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/isnull-suffix"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-isnull-suffix"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-isnull-suffix"));

        Files.writeString(sourceDir.resolve("Memo.java"), """
                package isnullsuffix;
                public class Memo {
                    public Long id;
                    public String name;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        Files.writeString(sourceDir.resolve("Memos.java"), """
                package isnullsuffix;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Memos extends CrudRepository<Memo, Long> {
                    List<Memo> findByNameIsNull();
                }
                """);

        TaskResult result = runTask(sourceDir, classesDir, generatedDir, "Memo.java", "Memos.java");

        assertFalse(result.success(), "findByNameIsNull must not silently query a 'name_is' column");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                        && d.getMessage(Locale.ROOT).contains("does not resolve on entity")),
                "expected the unresolved-property diagnostic:" + System.lineSeparator() + result.diagnostics());
    }

    @Test
    void rendersOrAndIgnoreCaseInTheGeneratedSql() throws Exception {
        // End-to-end proof that the parse reaches the baked SQL: the connector and the case flag used
        // to be dropped between the method-name parse and emission, so an Or finder rendered AND.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/or-sql"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-or-sql"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-or-sql"));

        Files.writeString(sourceDir.resolve("Memo.java"), """
                package orsql;
                public class Memo {
                    public Long id;
                    public String text;
                    public String tag;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getText() { return text; }
                    public void setText(String text) { this.text = text; }
                    public String getTag() { return tag; }
                    public void setTag(String tag) { this.tag = tag; }
                }
                """);
        Files.writeString(sourceDir.resolve("Memos.java"), """
                package orsql;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Memos extends CrudRepository<Memo, Long> {
                    List<Memo> findByTextOrTag(String text, String tag);
                    List<Memo> findByTextIgnoreCaseLike(String text);
                    List<Memo> findByTextNull();
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Memo.java", "Memos.java");

        String impl = sqlFlat(generatedDir.resolve("orsql/MemosImpl.java"));
        assertTrue(impl.contains("FROM \"memo\" WHERE \"text\" = :text OR \"tag\" = :tag"),
                "an Or finder must render OR:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("WHERE LOWER(\"text\") LIKE LOWER(:text)"),
                "an IgnoreCase finder must fold both sides:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("WHERE \"text\" IS NULL"),
                "a nullary operator binds no argument:" + System.lineSeparator() + impl);
    }

    @Test
    void distinctSubjectKeywordRendersSelectDistinct() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/distinct"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-distinct"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-distinct"));

        Files.writeString(sourceDir.resolve("Memo.java"), """
                package distinctq;
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
                package distinctq;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Memos extends CrudRepository<Memo, Long> {
                    List<Memo> findDistinctByText(String text);
                    long countDistinctByText(String text);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Memo.java", "Memos.java");

        String impl = sqlFlat(generatedDir.resolve("distinctq/MemosImpl.java"));
        assertTrue(impl.contains("SELECT DISTINCT \"id\", \"text\" FROM \"memo\" WHERE \"text\" = :text"),
                "a Distinct find must select distinct rows:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("SELECT COUNT(DISTINCT \"id\") FROM \"memo\" WHERE \"text\" = :text"),
                "a Distinct count must count distinct identifiers:" + System.lineSeparator() + impl);

        String queries = sqlFlat(generatedDir.resolve("distinctq/MemosQueries.java"));
        // Pin the accessor body, not a bare "return true;": that literal appears in ANY boolean
        // accessor in this file, so it would still pass if distinct() regressed to return false.
        assertTrue(queries.contains("boolean distinct() { return true; }"),
                "the published query metadata must report distinct:" + System.lineSeparator() + queries);
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
    void rejectsEntityWithoutAnIdentifier() throws Exception {
        // Inventing an identifier produced a row mapper calling setId(...) on an entity with no such
        // method: an uncompilable generated source with no hint of the real cause.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/no-identity"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-no-identity"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-no-identity"));

        Files.writeString(sourceDir.resolve("Tag.java"), """
                package noidentity;
                import io.github.fludakit.data.annotation.Entity;
                @Entity
                public class Tag {
                    String label;
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                }
                """);
        Files.writeString(sourceDir.resolve("Tags.java"), """
                package noidentity;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Tags extends CrudRepository<Tag, Long> {
                }
                """);

        TaskResult result = runTask(sourceDir, classesDir, generatedDir, "Tag.java", "Tags.java");

        assertFalse(result.success(), "an entity with no identifier must be rejected");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                        && d.getMessage(Locale.ROOT).contains("declares no identifier")),
                "expected the missing-identity diagnostic:" + System.lineSeparator() + result.diagnostics());
    }

    @Test
    void byAnnotationGovernsThePredicateAndTheMethodNameIsNotResolved() throws Exception {
        // @By carries no operator, so the implied operator is equality and the name is not parsed:
        // a `Like` keyword in the method name must NOT override the annotation.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/by-governs"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-by-governs"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-by-governs"));

        Files.writeString(sourceDir.resolve("Note.java"), """
                package bygoverns;
                public class Note {
                    public Long id;
                    public String title;
                    public String body;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public String getBody() { return body; }
                    public void setBody(String body) { this.body = body; }
                }
                """);
        Files.writeString(sourceDir.resolve("Notes.java"), """
                package bygoverns;
                import jakarta.data.repository.By;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Find;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Notes extends CrudRepository<Note, Long> {
                    @Find List<Note> findByTitleLike(@By("title") String pattern);
                    @Find List<Note> findByBodyIn(@By("body") String body);
                    // No @By and a name that is not a query-by-method name at all: both are irrelevant.
                    @Find List<Note> searchTitleLike(String marker);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Note.java", "Notes.java");

        String impl = sqlFlat(generatedDir.resolve("bygoverns/NotesImpl.java"));
        assertTrue(impl.contains("FROM \"note\" WHERE \"title\" = :pattern"),
                "the @By property with the implied equality must win over the name's Like:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("FROM \"note\" WHERE \"body\" = :body"),
                "the @By property with the implied equality must win over the name's In:"
                        + System.lineSeparator() + impl);
        // Without @By the criteria are the PARAMETER NAMES, with the implied equality: the name's
        // "TitleLike" contributes nothing, so the property is "marker" and the operator is "=".
        assertTrue(impl.contains("FROM \"note\" WHERE \"marker\" = :marker"),
                "without @By the parameter name is the property:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("\"title\" LIKE") && !impl.contains("\"title\" IN"),
                "an annotated method's name must never be analyzed for a property or an operator:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void lifecycleAnnotationIsDecidedByNameIndependentSignature() throws Exception {
        // Tier 1: a lifecycle annotation defines the write by signature alone, so the method name is
        // irrelevant. "store"/"persist" are neither built-in names nor query-by-method names.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/lifecycle-free-name"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-lifecycle-free-name"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-lifecycle-free-name"));

        Files.writeString(sourceDir.resolve("Note.java"), """
                package lifecyclefreename;
                public class Note {
                    public Long id;
                    public String title;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                }
                """);
        Files.writeString(sourceDir.resolve("Notes.java"), """
                package lifecyclefreename;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import jakarta.data.repository.Insert;
                import jakarta.data.repository.Save;
                @Repository
                public interface Notes extends CrudRepository<Note, Long> {
                    @Insert void store(Note note);
                    @Save Note persist(Note note);
                    @Save java.util.List<Note> persistAll(java.util.List<Note> notes);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Note.java", "Notes.java");

        String impl = sqlFlat(generatedDir.resolve("lifecyclefreename/NotesImpl.java"));
        assertTrue(impl.contains("public void store(Note note)"),
                "a lifecycle method named freely must be generated:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("public Note persist(Note note)"),
                "a lifecycle method named freely must be generated:" + System.lineSeparator() + impl);
    }

    @Test
    void builtInNameWinsOverItsOwnFindAnnotation() throws Exception {
        // Tier 2 before tier 3: CrudRepository.findAll carries @Find, but it is a built-in and must be
        // matched by name — otherwise the query-by-method-name parser is handed "findAll" and rejects
        // it as an unsupported subject.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/builtin-findall"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-builtin-findall"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-builtin-findall"));

        Files.writeString(sourceDir.resolve("Note.java"), """
                package builtinfindall;
                public class Note {
                    public Long id;
                    public String title;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                }
                """);
        Files.writeString(sourceDir.resolve("Notes.java"), """
                package builtinfindall;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.stream.Stream;
                @Repository
                public interface Notes extends CrudRepository<Note, Long> {
                    Stream<Note> findAll();
                    boolean existsById(Long id);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Note.java", "Notes.java");

        String impl = sqlFlat(generatedDir.resolve("builtinfindall/NotesImpl.java"));
        assertTrue(impl.contains("public Stream<Note> findAll()"),
                "a built-in findAll must be generated:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("public boolean existsById(Long id)"),
                "a built-in existsById must be generated:" + System.lineSeparator() + impl);
    }

    @Test
    void rejectsLifecycleMethodWithUnsupportedSignature() throws Exception {
        // A lifecycle annotation pins the method to a write. Without this check the emitter falls
        // through to the query path and silently generates a predicate-less SELECT over the table.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/lifecycle-shape"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-lifecycle-shape"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-lifecycle-shape"));

        Files.writeString(sourceDir.resolve("Note.java"), """
                package lifecycleshape;
                public class Note {
                    public Long id;
                    public String title;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                }
                """);
        Files.writeString(sourceDir.resolve("Notes.java"), """
                package lifecycleshape;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import jakarta.data.repository.Save;
                @Repository
                public interface Notes extends CrudRepository<Note, Long> {
                    @Save void publish(String title);
                }
                """);

        TaskResult result = runTask(sourceDir, classesDir, generatedDir, "Note.java", "Notes.java");

        assertFalse(result.success(), "a lifecycle method with a non-entity parameter must be rejected");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                        && d.getMessage(Locale.ROOT).contains("carries a lifecycle annotation and must accept")),
                "expected the lifecycle shape diagnostic:" + System.lineSeparator() + result.diagnostics());
    }

    @Test
    void rejectsUnparseableQueryByNameMethod() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/qbn-unparseable"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-qbn-unparseable"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-qbn-unparseable"));

        Files.writeString(sourceDir.resolve("Memo.java"), """
                package qbnunparseable;
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
                package qbnunparseable;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Memos extends CrudRepository<Memo, Long> {
                    List<Memo> findByTextAnd(String text);
                }
                """);

        TaskResult result = runTask(sourceDir, classesDir, generatedDir, "Memo.java", "Memos.java");

        assertFalse(result.success(), "an unparseable query-by-method name must not silently compile");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                        && d.getMessage(Locale.ROOT).contains("not a valid query-by-method name")),
                "expected the unparseable-name diagnostic:" + System.lineSeparator() + result.diagnostics());
    }

    @Test
    void rejectsUnannotatedAbstractMethodOnFreeStyleRepository() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/freestyle-invalid"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-freestyle-invalid"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-freestyle-invalid"));

        Files.writeString(sourceDir.resolve("Customer.java"), """
                package freestyleinvalid;
                public class Customer {
                    public Long id;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                }
                """);
        Files.writeString(sourceDir.resolve("CustomerRepository.java"), """
                package freestyleinvalid;
                import jakarta.data.repository.Find;
                import jakarta.data.repository.Repository;
                @Repository
                public interface CustomerRepository {
                    @Find Customer annotated();
                    Customer findByName(String name);
                }
                """);

        TaskResult result = runTask(sourceDir, classesDir, generatedDir, "Customer.java", "CustomerRepository.java");
        List<Diagnostic<? extends JavaFileObject>> diagnostics = result.diagnostics();
        assertTrue(diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                        && d.getMessage(Locale.ROOT).contains("must declare a Jakarta Data query/lifecycle annotation")),
                "expected unannotated method diagnostic:" + System.lineSeparator() + diagnostics);
    }

    @Test
    void rejectsRepositoryMethodCombiningOperationAnnotations() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/combined-annotations"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-combined-annotations"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-combined-annotations"));

        Files.writeString(sourceDir.resolve("Customer.java"), """
                package combinedannotations;
                public class Customer {
                    public Long id;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                }
                """);
        Files.writeString(sourceDir.resolve("CustomerRepository.java"), """
                package combinedannotations;
                import jakarta.data.repository.Find;
                import jakarta.data.repository.Query;
                import jakarta.data.repository.Repository;
                @Repository
                public interface CustomerRepository {
                    @Find @Query("SELECT id FROM customer")
                    Customer customer();
                }
                """);

        TaskResult result = runTask(sourceDir, classesDir, generatedDir, "Customer.java", "CustomerRepository.java");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                        && d.getMessage(Locale.ROOT).contains("must not combine query and lifecycle annotations")),
                "expected conflicting operation annotation diagnostic:" + System.lineSeparator()
                        + result.diagnostics());
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

                    @Query("SELECT id, title FROM book WHERE title = :title AND note <> '?1'")
                    Optional<Book> nativeByNamedTitle(String title);

                    @Query("SELECT id, title FROM book WHERE title = ?1")
                    Book[] nativeBooksByTitle(String title);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Book.java", "Shelf.java");

        String impl = Files.readString(generatedDir.resolve("demo3/ShelfImpl.java"));
        assertTrue(impl.contains("SqlDialect SQL_DIALECT = H2Dialect.INSTANCE"),
                "generated SQL hooks must be centralized behind the dialect field:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains(".query(new BookRowMapper())"),
                "convention find must use the generated mapper:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".query(Book.class)"),
                "@Query native SQL must keep class-based mapping:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("SELECT id, title FROM book WHERE title = ?1"),
                "@Query native SQL must pass through without dialect rewriting:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(SQL_NATIVE_BY_TITLE).param(title)"),
                "positional @Query parameters must bind in declaration order:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(SQL_NATIVE_BY_NAMED_TITLE).param(\"title\", title)"),
                "named @Query parameters may use the source parameter name:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(SQL_NATIVE_BOOKS_BY_TITLE).param(title)")
                        && impl.contains(".list().toArray(Book[]::new)"),
                "array @Query results must bind positional arguments and return the declared array:"
                        + System.lineSeparator() + impl);
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
        String md = Files.readString(generatedDir.resolve("cls/BookMetadata.java"));
        assertTrue(md.contains("mappings.put(\"author\", new ColumnRef(\"author_id\", \"book\", null, List.of()))"),
                "association must contribute its fk column to the descriptor's typed mapping index:"
                        + System.lineSeparator() + md);
        assertTrue(md.contains("mappings.put(\"title\", new ColumnRef(\"title\", \"book\", null, List.of()))"),
                "scalar column must be present:" + System.lineSeparator() + md);
        assertTrue(!md.replace(" ", "").contains("\"author_name\""),
                "an @Association must NOT flatten the target's fields into the owner's columns:"
                        + System.lineSeparator() + md);
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
    void generatedMetadataImplementsEntityMetadataWithKindsAndTargets() throws Exception {
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
        assertTrue(md.contains("implements EntityMetadata<Book>"),
                "generated metadata must implement the generic interface:" + System.lineSeparator() + md);
        assertTrue(md.contains("import io.github.fludakit.data.metadata.EntityMetadata;"), md);
        assertTrue(md.contains("public static final BookMetadata INSTANCE"), md);
        // Decision 1: the descriptor returns the _Book metamodel field instances, not literals,
        // and the association is a navigable member class implementing the FK attribute interface.
        assertTrue(md.contains("_Book.author"),
                "@Association must be exposed through the navigable metamodel member:" + System.lineSeparator() + md);
        String mm = Files.readString(generatedDir.resolve("meta/_Book.java")).replaceAll("\\s+", "");
        assertTrue(mm.contains("_BookAuthorauthor=new_BookAuthor();"),
                "the association member must instantiate its nav class:" + System.lineSeparator() + mm);
        // The unmapped target (meta.Author has no repository) still gets the nav class with the
        // name() accessor alone and NO leaf fields (Task 6 gate degradation; D3 slim attributes:
        // column()/target()/targetDescriptor() plumbing was removed from navigable attributes).
        String nav = Files.readString(generatedDir.resolve("meta/_BookAuthor.java")).replaceAll("\\s+", "");
        assertTrue(nav.contains("publicfinalclass_BookAuthorimplementsEntityAttribute<Book,Author>"), nav);
        assertTrue(nav.contains("publicStringname(){return\"author\";}"), nav);
        assertTrue(!nav.contains("IdAttributeRecord"),
                "an unmapped relation target must carry no leaf fields:" + System.lineSeparator() + nav);
        // Plan A column constants are preserved:
        assertTrue(md.contains("public static final String TABLE_NAME = \"book\";"), md);
        assertTrue(md.contains("public static final String TITLE = \"title\";"), md);
        // The descriptor derives its table-local column view from the typed mapping index.
        String mflat = Files.readString(generatedDir.resolve("meta/BookMetadata.java")).replaceAll("\\s+", "");
        assertTrue(mflat.contains("mappings=newLinkedHashMap<>();")
                        && mflat.contains("mappings.put(\"id\",newColumnRef(\"id\",\"book\",null,List.of()))")
                        && mflat.contains("mappings.put(\"title\",newColumnRef(\"title\",\"book\",null,List.of()))")
                        && !mflat.contains("columns(){"),
                "typed mappings supply columns without generated duplicate state:" + System.lineSeparator() + mflat);
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
        assertTrue(vmd.contains("implements EntityMetadata<Address>"), vmd);
        assertTrue(vmd.contains("public static final AddressMetadata INSTANCE"), vmd);
        assertTrue(!vmd.contains("TABLE_NAME"),
                "a value object has no table and must not emit TABLE_NAME:" + System.lineSeparator() + vmd);
        assertTrue(vmd.contains("public String tableName()") && vmd.contains("return null;"),
                "value objects report a null table name:" + System.lineSeparator() + vmd);
        // A VO has no metamodel, so its members are fresh attribute-record literals (Decision 1's
        // fallback mode) and the id accessor is a typed null. The list carries the ROOT members only:
        // the nested EMBEDDED container appears once and its leaves are no longer flat attributes.
        assertTrue(vmd.contains("public IdAttribute<Address> id()") && vmd.contains("return null;"),
                "value objects report a null id:" + System.lineSeparator() + vmd);
        assertTrue(vmd.contains("public static final String CITY = \"city\";"), vmd);
        String vflat = Files.readString(voMetadata).replaceAll("\\s+", "");
        assertTrue(vflat.contains("attributes(){returnList.of("
                        + "newTextScalarAttributeRecord<Address>(\"city\",\"city\"),"
                        + "newEmbeddableAttributeRecord<Address,GeoPoint>(\"point\",\"point\"));}"),
                "VO attributes() must list root members once, without flattened leaves:"
                        + System.lineSeparator() + vflat);
        // D3: attributes navigate by name; the nested value object is referenced through the
        // record's TYPE ARGUMENTS (the INSTANCE plumbing went with column()). Transitive VO
        // generation is still pinned by the GeoPointMetadata existence check below.
        assertTrue(vmd.contains("new EmbeddableAttributeRecord<Address,GeoPoint>(\"point\", \"point\")"),
                "embedded attribute must still name the nested value object type:" + System.lineSeparator() + vmd);
        assertTrue(vflat.contains("newEmbeddableAttributeRecord<Address,GeoPoint>(\"point\",\"point\")"),
                "the embedded literal must carry the VO type arguments:" + System.lineSeparator() + vflat);
        // ... and the VO's flattened leaves are indexed as PathAttributes.
        assertTrue(vflat.contains("mappings=newLinkedHashMap<>();")
                        && vflat.contains("mappings.put(\"point.lat\",newColumnRef(\"point_lat\",null,null,List.of()))")
                        && !vflat.contains("columns(){"),
                "VO leaves must be indexed by PathAttribute:" + System.lineSeparator() + vflat);
        // ... which must itself have been generated (transitive EMBEDDED tree walk).
        Path innerMetadata = generatedDir.resolve("emb/GeoPointMetadata.java");
        assertTrue(Files.exists(innerMetadata), "Expected generated " + innerMetadata);
        String imd = Files.readString(innerMetadata).replaceAll("\\s+", " ");
        assertTrue(imd.contains("public static final String LAT = \"lat\";"), imd);
        assertTrue(imd.contains("new SortableScalarAttributeRecord<GeoPoint>(\"lat\", \"lat\")"), imd);

        // The owner's descriptor lists the root metamodel members (scalar + embedded nav) once, and
        // the navigable member classes carry the flattened leaves as owner-typed fields.
        String cmd = Files.readString(generatedDir.resolve("emb/ContactMetadata.java")).replaceAll("\\s+", " ");
        assertTrue(cmd.contains("return List.of(_Contact.name, _Contact.address);"),
                "attributes() must expose containers once, no flattened leaves:" + System.lineSeparator() + cmd);
        String cflat = Files.readString(generatedDir.resolve("emb/ContactMetadata.java")).replaceAll("\\s+", "");
        assertTrue(cflat.contains("mappings.put(\"address.city\",newColumnRef(\"address_city\",\"contact\",null,List.of()))")
                        && cflat.contains("mappings.put(\"address.point.lat\",newColumnRef(\"address_point_lat\",\"contact\",null,List.of()))")
                        && !cflat.contains("columns(){"),
                "the typed index flattens the embedded chain and derives root columns:"
                        + System.lineSeparator() + cflat);
        String addrNav = Files.readString(generatedDir.resolve("emb/_ContactAddress.java")).replaceAll("\\s+", "");
        assertTrue(addrNav.contains("publicfinalclass_ContactAddressimplementsEmbeddableAttribute<Contact,Address>"),
                addrNav);
        assertTrue(addrNav.contains(
                "TextScalarAttribute<Contact>city=newTextScalarAttributeRecord<>(\"city\",\"address.city\");"),
                "embedded leaf must be an owner-typed nav field (D4: path only, no column slot):"
                        + System.lineSeparator() + addrNav);
        assertTrue(addrNav.contains("_ContactAddressPointpoint=new_ContactAddressPoint();"),
                "nested embedded must recurse into its own nav class:" + System.lineSeparator() + addrNav);
        // D3: the container contract is the name() accessor alone; the embedded() INSTANCE
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
    void appAssignedIdInsertBindsIdParameterWithoutKeyholderRoundtrip() throws Exception {
        // S-4 / T11 P1: generated-key coercion becomes type-driven. String/UUID ids are
        // application-assigned, so the generated INSERT must BIND the id column parameter and skip the
        // GeneratedKeyHolder/getKey roundtrip entirely — the old longValue() fallback was runtime-broken
        // for them (and did not compile at all in a repository impl: that compile failure IS the BASE
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

        // Long-id byte parity: the keyholder emission is textually identical to the pre-Task-3 shape.
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

        // Flatten JavaPoet's soft wraps so the positional pins hold verbatim: relations are now
        // NAVIGABLE member classes whose leaf fields carry the owner-rooted dot paths while the fk
        // column and the resolved inverse ride the descriptor's columnRefs() literals, so ONLY
        // an exact assertion on either surface detects a swapped argument (M-6).
        String pm = Files.readString(generatedDir.resolve("rel/_Parent.java")).replaceAll("\\s+", "");
        assertTrue(pm.contains("_ParentPrimaryprimary=new_ParentPrimary();"),
                "the FK member must instantiate its nav class:" + System.lineSeparator() + pm);
        assertTrue(pm.contains("_ParentChildrenchildren=new_ParentChildren();"),
                "the one-to-many member must instantiate its nav class:" + System.lineSeparator() + pm);

        String fkNav = Files.readString(generatedDir.resolve("rel/_ParentPrimary.java")).replaceAll("\\s+", "");
        assertTrue(fkNav.contains("publicfinalclass_ParentPrimaryimplementsEntityAttribute<Parent,Child>"), fkNav);
        assertTrue(fkNav.contains("publicStringpath(){return\"primary\";}"), fkNav);
        assertTrue(fkNav.contains("IdAttribute<Parent>id=newIdAttributeRecord<>(\"id\",\"primary.id\");"),
                "the managed target's id must surface as an owner-typed leaf field:" + System.lineSeparator() + fkNav);
        assertTrue(fkNav.contains("ScalarAttribute<Parent>owner=newScalarAttributeRecord<>(\"owner\",\"primary.owner\");"),
                "the target's own FK column must surface as a plain leaf:" + System.lineSeparator() + fkNav);
        assertTrue(!fkNav.contains("targetDescriptor") && !fkNav.contains("publicStringcolumn()"),
                "D3 removed the column()/targetDescriptor() plumbing from the nav classes:" + System.lineSeparator() + fkNav);

        String otmNav = Files.readString(generatedDir.resolve("rel/_ParentChildren.java")).replaceAll("\\s+", "");
        // D3: the one-to-many rides the unified ListAttribute; the inverseName()/childFkColumn()
        // contract is gone — the resolved inverse fk is pinned through columnRefs below.
        assertTrue(otmNav.contains("publicfinalclass_ParentChildrenimplementsListAttribute<Parent,Child>"), otmNav);
        assertTrue(otmNav.contains("publicStringpath(){return\"children\";}"), otmNav);
        assertTrue(otmNav.contains("IdAttribute<Parent>id=newIdAttributeRecord<>(\"id\",\"children.id\");"), otmNav);
        String pmd = Files.readString(generatedDir.resolve("rel/ParentMetadata.java")).replaceAll("\\s+", "");
        assertTrue(pmd.contains("mappings.put(\"primary.id\",newColumnRef(\"id\",\"child\",\"primary\",List.of(newJoinRef(\"parent\",null,\"primary_id\",\"child\",\"primary\",\"id\"))))"),
                "columnRefs must pin the FK join verbatim:" + System.lineSeparator() + pmd);
        assertTrue(pmd.contains("mappings.put(\"children.id\",newColumnRef(\"id\",\"child\",\"children\",List.of(newJoinRef(\"parent\",null,\"id\",\"child\",\"children\",\"owner_id\"))))"),
                "columnRefs must pin the one-to-many inverse join verbatim:" + System.lineSeparator() + pmd);

        String md = Files.readString(generatedDir.resolve("rel/ParentMetadata.java")).replaceAll("\\s+", " ");
        assertTrue(md.contains("return List.of(_Parent.name, _Parent.primary, _Parent.children);"),
                "attributes() must return the metamodel field instances, not literals:" + System.lineSeparator() + md);
        String mflat = Files.readString(generatedDir.resolve("rel/ParentMetadata.java")).replaceAll("\\s+", "");
        assertTrue(mflat.contains("mappings.put(\"name\",newColumnRef(\"name\",\"parent\",null,List.of()))")
                        && mflat.contains("mappings.put(\"primary\",newColumnRef(\"primary_id\",\"parent\",null,List.of()))")
                        && !mflat.contains("columns(){"),
                "typed mappings list the local table's columns; the one-to-many contributes none:"
                        + System.lineSeparator() + mflat);
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
        // D3: attributes navigate by name; the nested value object survives as the record's
        // type argument instead of the removed embedded-INSTANCE plumbing.
        assertTrue(vmd.contains("new EmbeddableAttributeRecord<Address,GeoPoint>(\"point\", \"point\")"),
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
        // D4: slim record constructors take the name only; column placement is descriptor-owned.
        assertTrue(mm.contains("IdAttribute<Book>id=newIdAttributeRecord<>(\"id\",ID);"), mm);
        assertTrue(mm.contains("TextScalarAttribute<Book>title=newTextScalarAttributeRecord<>(\"title\",TITLE);"), mm);
        // The metamodel is additive: the runtime metadata remains and derives its column view from
        // the typed mapping index.
        assertTrue(Files.exists(generatedDir.resolve("mmbook/BookMetadata.java")),
                "the internal <Entity>Metadata must still be generated");
        String mdb = Files.readString(generatedDir.resolve("mmbook/BookMetadata.java")).replaceAll("\\s+", "");
        assertTrue(mdb.contains("mappings.put(\"id\",newColumnRef(\"id\",\"book\",null,List.of()))")
                        && mdb.contains("mappings.put(\"title\",newColumnRef(\"title\",\"book\",null,List.of()))")
                        && !mdb.contains("columns(){"), mdb);
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
        assertTrue(mm.contains("StringID=\"id\";"), mm);
        assertTrue(mm.contains("StringADDRESS=\"address\";"), mm);
        // The embedded chain no longer flattens into the root interface: no dotted constants and no
        // leaf fields survive there — the leaves live in the navigable member classes.
        assertTrue(!mm.contains("StringADDRESS_CITY"), mm);
        assertTrue(!mm.contains("StringADDRESS_POINT_LAT"), mm);
        assertTrue(mm.contains("_ProfileAddressaddress=new_ProfileAddress();"), mm);
        assertTrue(mm.contains("IdAttribute<Profile>id=newIdAttributeRecord<>(\"id\",ID);"), mm);

        String addr = Files.readString(generatedDir.resolve("mmgp/_ProfileAddress.java")).replaceAll("\\s+", "");
        assertTrue(addr.contains("publicfinalclass_ProfileAddressimplementsEmbeddableAttribute<Profile,Address>"), addr);
        assertTrue(addr.contains("TextScalarAttribute<Profile>city="
                        + "newTextScalarAttributeRecord<>(\"city\",\"address.city\");"),
                "embedded leaf keeps the dot-path name (D4: column placement is descriptor-owned):"
                        + System.lineSeparator() + addr);
        assertTrue(addr.contains("SortableScalarAttribute<Profile>zip="
                        + "newSortableScalarAttributeRecord<>(\"zip\",\"address.zip\");"), addr);
        assertTrue(addr.contains("_ProfileAddressPointpoint=new_ProfileAddressPoint();"),
                "nested embedded recurses into its own nav class instead of flat leaves:" + System.lineSeparator() + addr);
        assertTrue(addr.contains("publicStringname(){return\"address\";}"), addr);
        assertTrue(addr.contains("publicStringpath(){return\"address\";}"), addr);
        assertTrue(!addr.contains("embedded()"),
                "D3 removed the embedded() plumbing override:" + System.lineSeparator() + addr);

        String point = Files.readString(generatedDir.resolve("mmgp/_ProfileAddressPoint.java")).replaceAll("\\s+", "");
        assertTrue(point.contains("publicfinalclass_ProfileAddressPointimplementsEmbeddableAttribute<Profile,GeoPoint>"),
                point);
        assertTrue(point.contains("SortableScalarAttribute<Profile>lat="
                        + "newSortableScalarAttributeRecord<>(\"lat\",\"address.point.lat\");"), point);
        assertTrue(point.contains("SortableScalarAttribute<Profile>lng="
                        + "newSortableScalarAttributeRecord<>(\"lng\",\"address.point.lng\");"), point);
        assertTrue(point.contains("publicStringname(){return\"point\";}"), point);
        assertTrue(point.contains("publicStringpath(){return\"address.point\";}"), point);
        // Value objects never get their own metamodel classes (aggregate roots only).
        assertTrue(!Files.exists(generatedDir.resolve("mmgp/_Address.java")),
                "no _VO class for a value object");
        assertTrue(!Files.exists(generatedDir.resolve("mmgp/_GeoPoint.java")),
                "no _VO class for a nested value object");

        // The owner descriptor: root members once, explicit flattened columns in legacy order.
        String cmd = Files.readString(generatedDir.resolve("mmgp/ProfileMetadata.java")).replaceAll("\\s+", " ");
        assertTrue(cmd.contains("return List.of(_Profile.address);"), cmd);
        String cmdFlat = Files.readString(generatedDir.resolve("mmgp/ProfileMetadata.java")).replaceAll("\\s+", "");
        assertTrue(cmdFlat.contains("mappings=newLinkedHashMap<>();")
                        && !cmdFlat.contains("columns(){"), cmdFlat);
        assertTrue(cmdFlat.contains("mappings.put(\"address.city\",newColumnRef(\"address_city\",\"profile\",null,List.of()))")
                        && cmdFlat.contains("mappings.put(\"address.point.lat\",newColumnRef(\"address_point_lat\",\"profile\",null,List.of()))"),
                "columnRefs should index embedded leaves by their generated PathAttribute instances:"
                        + System.lineSeparator() + cmdFlat);
    }

    @Test
    void nestedValueObjectNavReferencesTopLevelMetadataSimpleName() throws Exception {
        // Task 6 re-check of the parked embeddedMetadataName bug: a @ValueObject declared as a nested
        // class has canonical FQN "pkg.Cfg.Geo", but its metadata is emitted as the TOP-LEVEL
        // <SimpleName>Metadata (pkg.GeoMetadata). The naive last-dot FQN split rendered
        // pkg.Cfg.GeoMetadata — a member class that never exists, breaking compilation of the nav.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/nestvo"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-nestvo"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-nestvo"));

        Files.writeString(sourceDir.resolve("Cfg.java"), """
                package nestvo;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Cfg {
                    @ValueObject
                    public static class Geo {
                        String tag;
                        public String getTag() { return tag; }
                        public void setTag(String tag) { this.tag = tag; }
                    }
                    Geo geo;
                    public Geo getGeo() { return geo; }
                    public void setGeo(Geo geo) { this.geo = geo; }
                }
                """);
        Files.writeString(sourceDir.resolve("Holder.java"), """
                package nestvo;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Holder {
                    @Identity Long id;
                    Cfg cfg;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Cfg getCfg() { return cfg; }
                    public void setCfg(Cfg cfg) { this.cfg = cfg; }
                }
                """);
        Files.writeString(sourceDir.resolve("Holders.java"), """
                package nestvo;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository interface Holders extends CrudRepository<Holder, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Cfg.java", "Holder.java", "Holders.java");

        Path geoMetadata = generatedDir.resolve("nestvo/GeoMetadata.java");
        assertTrue(Files.exists(geoMetadata), "nested VO metadata is a top-level <SimpleName>Metadata: " + geoMetadata);
        String cfgNav = Files.readString(generatedDir.resolve("nestvo/_HolderCfg.java")).replaceAll("\\s+", "");
        assertTrue(cfgNav.contains("_HolderCfgGeogeo=new_HolderCfgGeo();"), cfgNav);
        String geoNav = Files.readString(generatedDir.resolve("nestvo/_HolderCfgGeo.java")).replaceAll("\\s+", "");
        assertTrue(geoNav.contains("publicfinalclass_HolderCfgGeoimplementsEmbeddableAttribute<Holder,Cfg.Geo>"), geoNav);
        // D3: the nav carries no GeoMetadata.INSTANCE wiring; the leaf pinned by the full
        // owner-rooted dot path proves the nested VO resolved through the TOP-LEVEL GeoMetadata
        // model (<SimpleName>Metadata naming, existence asserted above).
        assertTrue(geoNav.contains("TextScalarAttribute<Holder>tag=newTextScalarAttributeRecord<>(\"tag\",\"cfg.geo.tag\");"),
                "the nav must expose the nested leaf via its owner-rooted dot path:" + System.lineSeparator() + geoNav);
        assertTrue(!geoNav.contains("Cfg.GeoMetadata"),
                "no qualified member-class metadata reference may be emitted:" + System.lineSeparator() + geoNav);
    }

    @Test
    void crossEntityNavClassNameCollisionDegradesToNote() throws Exception {
        // Task 6 fix review I-1: the nav-class collision guard must be GLOBAL across the
        // processing session, not per entity. Order.item (a relation member) names its nav class
        // pkg._OrderItem — the exact FQN of the OrderItem root's own static metamodel. The old
        // per-entity `claimed` set never saw the other entity's name, so the second
        // Filer.createSourceFile threw (printed as ERROR) and aborted the user build instead of
        // degrading. The metamodel wins the name (pre-claimed in the round pre-scan); the nav
        // degrades to the plain attribute record with a NOTE — and the whole build still compiles.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/ncol"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-ncol"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-ncol"));

        Files.writeString(sourceDir.resolve("OrderItem.java"), """
                package ncol;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class OrderItem {
                    @Identity Long id;
                    String sku;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getSku() { return sku; }
                    public void setSku(String sku) { this.sku = sku; }
                }
                """);
        Files.writeString(sourceDir.resolve("Order.java"), """
                package ncol;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Order {
                    @Identity Long id;
                    OrderItem item;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public OrderItem getItem() { return item; }
                    public void setItem(OrderItem item) { this.item = item; }
                }
                """);
        Files.writeString(sourceDir.resolve("Orders.java"), """
                package ncol;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Orders extends CrudRepository<Order, Long> { }
                """);
        Files.writeString(sourceDir.resolve("OrderItems.java"), """
                package ncol;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface OrderItems extends CrudRepository<OrderItem, Long> { }
                """);

        // compileWithDiagnostics asserts the whole task succeeds: at BASE (per-entity claimed) the
        // second createSourceFile of ncol._OrderItem printed an ERROR and failed this.
        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(
                sourceDir, classesDir, generatedDir,
                "Order.java", "OrderItem.java", "Orders.java", "OrderItems.java");

        // The root entity's OWN metamodel wins the name: _OrderItem is the interface, not a nav class.
        String itemMm = Files.readString(generatedDir.resolve("ncol/_OrderItem.java")).replaceAll("\\s+", "");
        assertTrue(itemMm.contains("publicinterface_OrderItem{"),
                "_OrderItem must be the OrderItem metamodel interface:" + System.lineSeparator() + itemMm);

        // The colliding nav degrades to the plain attribute record inside _Order.
        String orderMm = Files.readString(generatedDir.resolve("ncol/_Order.java")).replaceAll("\\s+", "");
        assertTrue(!orderMm.contains("new _OrderItem()"),
                "the colliding nav must NOT be instantiated:" + System.lineSeparator() + orderMm);
        assertTrue(orderMm.contains(
                        "EntityAttribute<Order,OrderItem>item=newEntityAttributeRecord<>(\"item\",ITEM);"),
                "the member must fall back to the plain entity attribute record:" + System.lineSeparator() + orderMm);

        // The degradation is recorded as a NOTE (never an ERROR — asserted by the success above).
        String notes = notesOf(diagnostics);
        assertTrue(notes.contains("static metamodel collision on navigable member 'item': type 'ncol._OrderItem' already claimed"),
                "the nav-collision NOTE must be recorded:" + System.lineSeparator() + notes);
    }

    @Test
    void valueObjectCycleStopsAtContainerOnlyNav() throws Exception {
        // Task 6 fix review M-1: a @ValueObject cycle (Root.head:Node, Node.tail:Tail, Tail.node:Node
        // — Java-legal) must bound at the descriptor layer and stop nav recursion: the back-reference
        // nav is a CONTAINER-ONLY class — it implements EmbeddableAttribute with NO leaf fields — and
        // the whole build still compiles (the null/cycle-target degrade at RepositoryProcessor's
        // embeddedNav, previously unpinned).
        Path sourceDir = Files.createDirectories(workspace.resolve("src/cyc"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-cyc"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-cyc"));

        Files.writeString(sourceDir.resolve("Node.java"), """
                package cyc;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Node {
                    String label;
                    Tail tail;
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                    public Tail getTail() { return tail; }
                    public void setTail(Tail tail) { this.tail = tail; }
                }
                """);
        Files.writeString(sourceDir.resolve("Tail.java"), """
                package cyc;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Tail {
                    String name;
                    Node node;
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public Node getNode() { return node; }
                    public void setNode(Node node) { this.node = node; }
                }
                """);
        Files.writeString(sourceDir.resolve("Root.java"), """
                package cyc;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Root {
                    @Identity Long id;
                    Node head;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Node getHead() { return head; }
                    public void setHead(Node head) { this.head = head; }
                }
                """);
        Files.writeString(sourceDir.resolve("Roots.java"), """
                package cyc;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Roots extends CrudRepository<Root, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Node.java", "Tail.java", "Root.java", "Roots.java");

        // The chain recurses one cycle lap: _RootHead → _RootHeadTail → the back-reference nav.
        String headNav = Files.readString(generatedDir.resolve("cyc/_RootHead.java")).replaceAll("\\s+", "");
        assertTrue(headNav.contains("TextScalarAttribute<Root>label=newTextScalarAttributeRecord<>(\"label\",\"head.label\");"),
                headNav);
        assertTrue(headNav.contains("_RootHeadTailtail=new_RootHeadTail();"), headNav);

        // The cycle-closing nav is CONTAINER-ONLY: no leaf records, no nested navs, just the
        // EmbeddableAttribute name() contract.
        String backNav = Files.readString(generatedDir.resolve("cyc/_RootHeadTailNode.java")).replaceAll("\\s+", "");
        assertTrue(backNav.contains("publicfinalclass_RootHeadTailNodeimplementsEmbeddableAttribute<Root,Node>"),
                "the back-reference must still be a typed container nav:" + System.lineSeparator() + backNav);
        assertTrue(!backNav.contains("AttributeRecord"),
                "the cycle nav must carry NO leaf fields:" + System.lineSeparator() + backNav);
        assertTrue(!backNav.contains("new _"),
                "the cycle nav must not recurse into further navs:" + System.lineSeparator() + backNav);
        assertTrue(backNav.contains("publicStringname(){return\"node\";}"), backNav);
        assertTrue(backNav.contains("publicStringpath(){return\"head.tail.node\";}"), backNav);
        // The cycle guard (descriptorFor) bounds the recursion with an EMPTY placeholder model that
        // keeps the type name — so the observable degrade is: no leaves, just the typed container
        // name() (D3 removed the embedded() INSTANCE plumbing entirely).
        assertTrue(!backNav.contains("embedded()"),
                "the slim back-reference nav must not emit the removed embedded() override:" + System.lineSeparator() + backNav);
    }

    @Test
    void relationAndElementCollectionNavLeavesPinFlattenedNaming() throws Exception {
        // Task 6 fix review M-2: pins the two leaf-naming paths that were only inspected —
        // (a) the FK nav flattening the TARGET's nested embedded VO into target-local chained
        // columns (depot.addr.street → "addr_street"), and (b) the EC nav leaves rebuilt from the
        // element VO's props (tags.sku → "sku"), verifying the "identical by construction" claim
        // for the elementColumns mirror.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/navleaf"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-navleaf"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-navleaf"));

        Files.writeString(sourceDir.resolve("Addr.java"), """
                package navleaf;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Addr {
                    String street;
                    Integer no;
                    public String getStreet() { return street; }
                    public void setStreet(String street) { this.street = street; }
                    public Integer getNo() { return no; }
                    public void setNo(Integer no) { this.no = no; }
                }
                """);
        Files.writeString(sourceDir.resolve("Depot.java"), """
                package navleaf;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Depot {
                    @Identity Long id;
                    String city;
                    Addr addr;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getCity() { return city; }
                    public void setCity(String city) { this.city = city; }
                    public Addr getAddr() { return addr; }
                    public void setAddr(Addr addr) { this.addr = addr; }
                }
                """);
        Files.writeString(sourceDir.resolve("Tags.java"), """
                package navleaf;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Tags {
                    String sku;
                    int qty;
                    public String getSku() { return sku; }
                    public void setSku(String sku) { this.sku = sku; }
                    public int getQty() { return qty; }
                    public void setQty(int qty) { this.qty = qty; }
                }
                """);
        Files.writeString(sourceDir.resolve("Load.java"), """
                package navleaf;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @AggregateRoot
                public class Load {
                    @Identity Long id;
                    Depot depot;
                    List<Tags> tags = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Depot getDepot() { return depot; }
                    public void setDepot(Depot depot) { this.depot = depot; }
                    public List<Tags> getTags() { return tags; }
                    public void setTags(List<Tags> tags) { this.tags = tags; }
                }
                """);
        Files.writeString(sourceDir.resolve("Loads.java"), """
                package navleaf;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Loads extends CrudRepository<Load, Long> { }
                """);
        Files.writeString(sourceDir.resolve("Depots.java"), """
                package navleaf;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Depots extends CrudRepository<Depot, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Addr.java", "Tags.java", "Depot.java", "Load.java", "Loads.java", "Depots.java");

        // (a) FK nav: owner-typed leaves over the target's flattened LOCAL paths; the target's
        // nested embedded VO flattens into target-local chained columns, dotted in the path.
        String fkNav = Files.readString(generatedDir.resolve("navleaf/_LoadDepot.java")).replaceAll("\\s+", "");
        assertTrue(fkNav.contains("publicfinalclass_LoadDepotimplementsEntityAttribute<Load,Depot>"), fkNav);
        assertTrue(fkNav.contains("IdAttribute<Load>id=newIdAttributeRecord<>(\"id\",\"depot.id\");"), fkNav);
        assertTrue(fkNav.contains("TextScalarAttribute<Load>city=newTextScalarAttributeRecord<>(\"city\",\"depot.city\");"), fkNav);
        assertTrue(fkNav.contains("TextScalarAttribute<Load>street=newTextScalarAttributeRecord<>(\"street\",\"depot.addr.street\");"),
                "the target's nested embedded leaf keeps the target-local dotted path:"
                        + System.lineSeparator() + fkNav);
        assertTrue(fkNav.contains("SortableScalarAttribute<Load>no=newSortableScalarAttributeRecord<>(\"no\",\"depot.addr.no\");"), fkNav);
        // D3/D4: the chained-column and fk-placement facts moved into the descriptor's generated
        // columnRefs() literals — pin the TARGET-LOCAL chained column and the join there.
        String lmd = Files.readString(generatedDir.resolve("navleaf/LoadMetadata.java")).replaceAll("\\s+", "");
        assertTrue(lmd.contains("mappings.put(\"depot.addr.street\",newColumnRef(\"addr_street\",\"depot\",\"depot\",List.of(newJoinRef(\"load\",null,\"depot_id\",\"depot\",\"depot\",\"id\"))))"),
                "columnRefs must pin the target-local chained column and the FK join:" + System.lineSeparator() + lmd);

        // (b) EC nav: leaves rebuilt from the element VO props must carry the elementColumns
        // paths/columns exactly (child table contributes no table prefix to the leaf columns).
        String ecNav = Files.readString(generatedDir.resolve("navleaf/_LoadTags.java")).replaceAll("\\s+", "");
        // D3: ListAttribute covers both one-to-many and element collections — the interface name
        // no longer separates them; the columnRefs join shape does (child table + parent fk).
        assertTrue(ecNav.contains("publicfinalclass_LoadTagsimplementsListAttribute<Load,Tags>"), ecNav);
        assertTrue(ecNav.contains("TextScalarAttribute<Load>sku=newTextScalarAttributeRecord<>(\"sku\",\"tags.sku\");"), ecNav);
        assertTrue(ecNav.contains("SortableScalarAttribute<Load>qty=newSortableScalarAttributeRecord<>(\"qty\",\"tags.qty\");"), ecNav);
        assertTrue(lmd.contains("mappings.put(\"tags.sku\",newColumnRef(\"sku\",\"load_tags\",\"tags\",List.of(newJoinRef(\"load\",null,\"id\",\"load_tags\",\"tags\",\"load_id\"))))"),
                "element-collection navigation must pin the child table and parent fk through the join:" + System.lineSeparator() + lmd);
    }

    @Test
    void staticMetamodelCoversRelationshipsAndSortPathsResolveThroughDescriptor() throws Exception {
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

        // Relationship properties surface as navigable member classes; the managed targets carry
        // owner-typed leaf fields for their flattened local paths plus the container contract.
        String pm = Files.readString(generatedDir.resolve("mmrel/_Parent.java")).replaceAll("\\s+", "");
        assertTrue(pm.contains("StringCHILDREN=\"children\";"), pm);
        assertTrue(pm.contains("_ParentChildrenchildren=new_ParentChildren();"), pm);
        assertTrue(pm.contains("StringPRIMARY=\"primary\";"), pm);
        assertTrue(pm.contains("_ParentPrimaryprimary=new_ParentPrimary();"), pm);
        assertTrue(pm.contains("TextScalarAttribute<Parent>name=newTextScalarAttributeRecord<>(\"name\",NAME);"), pm);
        String fkNav = Files.readString(generatedDir.resolve("mmrel/_ParentPrimary.java")).replaceAll("\\s+", "");
        assertTrue(fkNav.contains("publicfinalclass_ParentPrimaryimplementsEntityAttribute<Parent,Child>"), fkNav);
        assertTrue(fkNav.contains("IdAttribute<Parent>id=newIdAttributeRecord<>(\"id\",\"primary.id\");"),
                "the child's id must be a nav leaf named by its owner-rooted path:" + System.lineSeparator() + fkNav);
        // D3: targetDescriptor() plumbing is gone; the FK target resolution rides the descriptor's
        // generated columnRefs() literals.
        String pmd = Files.readString(generatedDir.resolve("mmrel/ParentMetadata.java")).replaceAll("\\s+", "");
        assertTrue(pmd.contains("mappings.put(\"primary.id\",newColumnRef(\"id\",\"child\",\"primary\",List.of(newJoinRef(\"parent\",null,\"primary_id\",\"child\",\"primary\",\"id\"))))"),
                "the generated columnRefs must carry the FK join:" + System.lineSeparator() + pmd);
        String otmNav = Files.readString(generatedDir.resolve("mmrel/_ParentChildren.java")).replaceAll("\\s+", "");
        assertTrue(otmNav.contains("publicfinalclass_ParentChildrenimplementsListAttribute<Parent,Child>"), otmNav);
        assertTrue(otmNav.contains("ScalarAttribute<Parent>owner=newScalarAttributeRecord<>(\"owner\",\"children.owner\");"),
                "the child's own FK column path must flatten into the collection nav:" + System.lineSeparator() + otmNav);
        assertTrue(pmd.contains("mappings.put(\"children.id\",newColumnRef(\"id\",\"child\",\"children\",List.of(newJoinRef(\"parent\",null,\"id\",\"child\",\"children\",\"owner_id\"))))"),
                "one-to-many navigation must join via the child table and the inverse fk:" + System.lineSeparator() + pmd);
        String cm = Files.readString(generatedDir.resolve("mmrel/_Child.java")).replaceAll("\\s+", "");
        assertTrue(cm.contains("_ChildOwnerowner=new_ChildOwner();"), cm);
        String ownerNav = Files.readString(generatedDir.resolve("mmrel/_ChildOwner.java")).replaceAll("\\s+", "");
        assertTrue(ownerNav.contains("publicfinalclass_ChildOwnerimplementsEntityAttribute<Child,Parent>"), ownerNav);
        assertTrue(ownerNav.contains("TextScalarAttribute<Child>name="
                        + "newTextScalarAttributeRecord<>(\"name\",\"owner.name\");"), ownerNav);
        assertTrue(ownerNav.contains("ScalarAttribute<Child>primary="
                        + "newScalarAttributeRecord<>(\"primary\",\"owner.primary\");"), ownerNav);

        // Sort dot paths now resolve through the descriptor: the flattened embedded leaves live in
        // the generated columns(), the paging impl calls <Entity>Metadata.columnRef, and an
        // EMBEDDED container contributes no column of its own.
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

        String md = Files.readString(generatedDir.resolve("mmrel/HolderMetadata.java"))
                .replaceAll("\\s+", "");
        assertTrue(md.contains("newColumnRef(\"loc_city\",\"holder\",null,List.of())"),
                "embedded leaf path must resolve to its flattened column:" + System.lineSeparator() + md);
        assertTrue(md.contains("newColumnRef(\"loc_zip\",\"holder\",null,List.of())"),
                "embedded leaf path must resolve to its flattened column:" + System.lineSeparator() + md);
        assertTrue(!md.contains("newColumnRef(\"loc\","),
                "no container column for an EMBEDDED property:" + System.lineSeparator() + md);
        assertTrue(md.contains("newColumnRef(\"id\",\"holder\",null,List.of())"),
                "the id column must remain:" + System.lineSeparator() + md);
        String impl = Files.readString(generatedDir.resolve("mmrel/HoldersImpl.java"));
        assertTrue(impl.contains("return HolderMetadata.INSTANCE.columnRef(property)"),
                "sort paths must resolve through the descriptor:" + System.lineSeparator() + impl);
    }

    @Test
    void bareEntityFieldOnEntityOwnerIsNonComposingAssociation() throws Exception {
        // Classification Table: a bare entity-typed field inside an @Entity owner is treated as an
        // ASSOCIATION (Prop.Reference composition=false), NOT a composition — so no two-phase loader is
        // emitted even though the target is managed. estore/team fixtures stay @AggregateRoot, so this
        // is the first behavior change and must not move their generated output.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/sbr"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-sbr"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-sbr"));

        Files.writeString(sourceDir.resolve("Owner.java"), """
                package sbr;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Owner {
                    @Identity Long id;
                    String label;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                }
                """);
        Files.writeString(sourceDir.resolve("TeamWithBareRef.java"), """
                package sbr;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class TeamWithBareRef {
                    @Identity Long id;
                    Owner owner;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Owner getOwner() { return owner; }
                    public void setOwner(Owner owner) { this.owner = owner; }
                }
                """);
        // Owner is a MANAGED target (has its own repository) so the pre-change composing classification
        // would emit a loader; only composition=false (this task) suppresses it.
        Files.writeString(sourceDir.resolve("OwnerRepository.java"), """
                package sbr;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface OwnerRepository extends CrudRepository<Owner, Long> { }
                """);
        Files.writeString(sourceDir.resolve("TeamWithBareRefRepository.java"), """
                package sbr;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface TeamWithBareRefRepository extends CrudRepository<TeamWithBareRef, Long> { }
                """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(
                sourceDir, classesDir, generatedDir,
                "Owner.java", "TeamWithBareRef.java", "OwnerRepository.java", "TeamWithBareRefRepository.java");

        String impl = Files.readString(generatedDir.resolve("sbr/TeamWithBareRefRepositoryImpl.java"))
                .replace("\\\"", "\"");
        assertTrue(!impl.contains("hydrateOwner"),
                "a bare entity field on an @Entity owner must NOT get a two-phase Owner loader:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("hydrateAll"),
                "no hydration umbrella when the only relationship is a non-composing association:"
                        + System.lineSeparator() + impl);

        // The metamodel still classifies owner as a foreign-key Reference (an association, not
        // embedded): a navigable member class implementing the EntityAttribute contract with the
        // managed target's leaf fields.
        String mm = Files.readString(generatedDir.resolve("sbr/_TeamWithBareRef.java")).replaceAll("\\s+", "");
        assertTrue(mm.contains("_TeamWithBareRefOwnerowner=new_TeamWithBareRefOwner();"),
                "a bare entity field on @Entity must classify as an FK Reference (association):"
                        + System.lineSeparator() + mm);
        String ownerNav = Files.readString(generatedDir.resolve("sbr/_TeamWithBareRefOwner.java")).replaceAll("\\s+", "");
        assertTrue(ownerNav.contains(
                "publicfinalclass_TeamWithBareRefOwnerimplementsEntityAttribute<TeamWithBareRef,Owner>"), ownerNav);
        assertTrue(ownerNav.contains("TextScalarAttribute<TeamWithBareRef>label="
                        + "newTextScalarAttributeRecord<>(\"label\",\"owner.label\");"), ownerNav);

        // The composition-gated managed-target NOTE is only emitted for composing references; an
        // association is skipped entirely, so no relationship NOTE appears for owner.
        String notes = notesOf(diagnostics);
        assertTrue(!notes.contains("relationship 'owner' on sbr.TeamWithBareRef"),
                "a non-composing association must not emit a relationship NOTE:" + System.lineSeparator() + notes);
    }

    @Test
    void valueObjectEntityListIsOneToManyNotElementCollection() throws Exception {
        // Requirements Lock item 3: a @ValueObject may hold entity collections; a List<@Entity> inside a
        // VO classifies as a one-to-many (Prop.EntityList), never an element collection. Driven through
        // an aggregate-root owner (the same idiom the embedded VO tests use to force VO model generation).
        Path sourceDir = Files.createDirectories(workspace.resolve("src/voel"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-voel"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-voel"));

        Files.writeString(sourceDir.resolve("Thing.java"), """
                package voel;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class Thing {
                    @Identity Long id;
                    String label;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                }
                """);
        Files.writeString(sourceDir.resolve("Holder.java"), """
                package voel;
                import io.github.fludakit.data.annotation.ValueObject;
                import java.util.ArrayList;
                import java.util.List;
                @ValueObject
                public class Holder {
                    List<Thing> things = new ArrayList<>();
                    public List<Thing> getThings() { return things; }
                    public void setThings(List<Thing> things) { this.things = things; }
                }
                """);
        Files.writeString(sourceDir.resolve("Widget.java"), """
                package voel;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Widget {
                    @Identity Long id;
                    Holder holder;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Holder getHolder() { return holder; }
                    public void setHolder(Holder holder) { this.holder = holder; }
                }
                """);
        Files.writeString(sourceDir.resolve("ThingRepository.java"), """
                package voel;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface ThingRepository extends CrudRepository<Thing, Long> { }
                """);
        Files.writeString(sourceDir.resolve("WidgetRepository.java"), """
                package voel;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface WidgetRepository extends CrudRepository<Widget, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Thing.java", "Holder.java", "Widget.java", "ThingRepository.java", "WidgetRepository.java");

        Path holderMetadata = generatedDir.resolve("voel/HolderMetadata.java");
        assertTrue(Files.exists(holderMetadata), "Expected generated " + holderMetadata);
        String vmd = Files.readString(holderMetadata).replaceAll("\\s+", " ");
        // D3 unifies one-to-many and element collections under ListAttribute: the attribute NAME
        // can no longer separate the two classifications at the descriptor surface. The
        // discriminating fact is pinned instead in the owner descriptors' columnRefs join
        // shapes (navleaf pins the element-collection child-table/parent-fk join; rel and mmrel
        // pin the one-to-many inverse-fk join). What stays pinned here: a List<@Entity> in a
        // value object exposes the navigable list attribute with the ENTITY element type and
        // never contributes a column of its own.
        assertTrue(vmd.contains("new ListAttributeRecord<Holder,Thing>(\"things\", \"things\")"),
                "a List<@Entity> in a value object must expose a navigable entity-typed list attribute:"
                        + System.lineSeparator() + vmd);
        assertTrue(!vmd.contains("new ColumnRef(\"things"),
                "an entity list contributes no descriptor-level column:" + System.lineSeparator() + vmd);
    }

    @Test
    void valueObjectDeclaringIdentityIsScalarWithNote() throws Exception {
        // Value object identity rule: a @ValueObject must have NO id. An @Identity field on a VO is a
        // scalar and emits an exact NOTE. Reached through an aggregate root that embeds the VO.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/void1"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-void1"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-void1"));

        Files.writeString(sourceDir.resolve("Point.java"), """
                package void1;
                import io.github.fludakit.data.annotation.Identity;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Point {
                    @Identity Long id;
                    double x;
                    double y;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public double getX() { return x; }
                    public void setX(double x) { this.x = x; }
                    public double getY() { return y; }
                    public void setY(double y) { this.y = y; }
                }
                """);
        Files.writeString(sourceDir.resolve("Line.java"), """
                package void1;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Line {
                    @Identity Long id;
                    Point start;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Point getStart() { return start; }
                    public void setStart(Point start) { this.start = start; }
                }
                """);
        Files.writeString(sourceDir.resolve("LineRepository.java"), """
                package void1;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface LineRepository extends CrudRepository<Line, Long> { }
                """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(
                sourceDir, classesDir, generatedDir, "Point.java", "Line.java", "LineRepository.java");

        String notes = notesOf(diagnostics);
        assertTrue(notes.contains(
                        "value object 'Point' declares 'id'; ignored — value objects have no identity"),
                "the exact value-object identity NOTE must be emitted:" + System.lineSeparator() + notes);

        // The id is treated as a scalar: it flattens into the owner table as start_id, and the VO
        // metadata reports a null id (value objects have no identity accessor).
        String vmd = Files.readString(generatedDir.resolve("void1/PointMetadata.java")).replaceAll("\\s+", " ");
        assertTrue(vmd.contains("public IdAttribute<Point> id()") && vmd.contains("return null;"),
                "a value object reports a null id even when it declares @Identity:" + System.lineSeparator() + vmd);
        String md = Files.readString(generatedDir.resolve("void1/LineMetadata.java"));
        assertTrue(md.contains("public static final String START_ID = \"start_id\";"),
                "the @Identity field must flatten as a scalar leaf column, not an identity:"
                        + System.lineSeparator() + md);
    }

    @Test
    void dynamicSortResolvesThroughDescriptorAndContributesJoins() throws Exception {
        // A paginated findAll(PageRequest, Order) builds its ORDER BY at RUNTIME from the caller's
        // Sort criteria. Task 7 replaces the compile-time string-switch column() helper with a
        // descriptor-backed resolveRef(...) -> <Entity>Metadata.columnRef(path), so each sort
        // resolves through the descriptor: relation-traversing paths contribute LEFT JOINs, while
        // embedded leaves stay on the root table (no join). This pins the emitted wiring; the
        // runtime relation-sort join behaviour is asserted by the ITs (Task 12).
        Path sourceDir = Files.createDirectories(workspace.resolve("src/dsort"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-dsort"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-dsort"));

        Files.writeString(sourceDir.resolve("GeoPoint.java"), """
                package dsort;
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
                package dsort;
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
        Files.writeString(sourceDir.resolve("Author.java"), """
                package dsort;
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
                package dsort;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Association;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Book {
                    @Identity Long id;
                    String title;
                    Address address;
                    @Association Author author;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public Address getAddress() { return address; }
                    public void setAddress(Address address) { this.address = address; }
                    public Author getAuthor() { return author; }
                    public void setAuthor(Author author) { this.author = author; }
                }
                """);
        Files.writeString(sourceDir.resolve("Books.java"), """
                package dsort;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Books extends CrudRepository<Book, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "GeoPoint.java", "Address.java", "Author.java", "Book.java", "Books.java");

        String impl = Files.readString(generatedDir.resolve("dsort/BooksImpl.java"))
                .replaceAll("\\s+", " ");

        // (1) The compile-time string-switch helper is gone; the descriptor-backed resolver replaces it.
        assertTrue(!impl.contains("String column(String property)"),
                "the old column(String) switch helper must be removed:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("ColumnRef resolveRef(String property)"),
                "expected a private static resolveRef(String) returning a ColumnRef:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("return BookMetadata.INSTANCE.columnRef(property)"),
                "resolveRef must resolve through the entity descriptor:" + System.lineSeparator() + impl);

        // (2) The runtime loop resolves each property ONCE and reuses the local for the join chain
        //     and ORDER BY, so descriptor work is not repeated.
        assertTrue(impl.contains("ColumnRef r = resolveRef(s.property())"),
                "the loop must resolve each property once:" + System.lineSeparator() + impl);

        // (3) ORDER BY is qualified through the descriptor-backed builder against the root table with
        //     an EXPLICIT direction (R-T3a), and the LEFT JOIN chain is computed once from the refs.
        assertTrue(impl.contains("sql.qualify(r)"),
                "ORDER BY must qualify the resolved ref against the root table:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("s.isAscending() ? \" ASC\" : \" DESC\""),
                "every ORDER BY term must carry an explicit ASC/DESC (R-T3a):" + System.lineSeparator() + impl);
        assertTrue(impl.contains("String joins = sql.joinClause(joinRefs)"),
                "the join clause must be computed from the descriptor and refs:" + System.lineSeparator() + impl);

        // (4) joins is spliced immediately after the FROM-bound find constant in the OFFSET read.
        //     Task 11 fix (R-T11c): Book declares a traversable prop (@Association author), so the
        //     emission picks between the plain SQL_FIND_ALL and the root-table-QUALIFIED
        //     SQL_FIND_ALL_QUALIFIED at runtime — the join is spliced onto whichever matches.
        assertTrue(impl.contains("jdbcClient.sql(((joins.isEmpty() && !keyed ? SQL_FIND_ALL : SQL_FIND_ALL_QUALIFIED) + joins + (orderBy.length() > 0"),
                "OFFSET statement must pick the qualified constant when joins are present:" + System.lineSeparator() + impl);
        // The qualified constant exists and qualifies EVERY root column (labels stay bare, so the
        // existing row mapper reads both variants).
        assertTrue(impl.contains("SELECT \\\"book\\\".\\\"id\\\", \\\"book\\\".\\\"title\\\", \\\"book\\\".\\\"address_city\\\","
                        + " \\\"book\\\".\\\"address_point_lat\\\", \\\"book\\\".\\\"address_point_lng\\\","
                        + " \\\"book\\\".\\\"author_id\\\" FROM \\\"book\\\""),
                "SQL_FIND_ALL_QUALIFIED must list root-table-qualified columns:" + System.lineSeparator() + impl);
    }

    @Test
    void aggregateWritesCascadeEntityCollectionChildren() throws Exception {
        // R-T11a: the composition lifecycle is bound to the aggregate root. An EntityList whose target
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
        // Child DELETE constant: child's own table keyed by the inverse fk (ElementList delete shape).
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
        // Update cascade: replace-all parity — DELETE by fk then re-insert the whole set, AFTER the
        // root UPDATE. Child ids are REGENERATED (documented choice: mirrors the element-collection
        // delete-whole-child-set + re-insert; no upsert-by-id machinery exists on either side).
        assertTrue(impl.contains("jdbcClient.sql(SQL_DELETE_PARTS).param(\"owner_id\", entity.getId()).update(); for (Part item : entity.getParts())"),
                "root update must delete the child set by fk then re-insert it (replace-all):" + System.lineSeparator() + impl);
        // deleteById cascade: children first (id value bound directly), then the root row.
        assertTrue(impl.contains("public void deleteById(Long id) { jdbcClient.sql(SQL_DELETE_PARTS).param(\"owner_id\", id).update(); jdbcClient.sql(SQL_DELETE).param(\"id\", id).update(); }"),
                "deleteById must remove cascaded children before the root:" + System.lineSeparator() + impl);
    }

    @Test
    void entityOwnerDoesNotCascadeEntityCollectionWrites() throws Exception {
        // Fix-round 2 I-1 (Requirements Lock md:33/md:34): the composition lifecycle is bound to the
        // root ONLY for @AggregateRoot owners. Inside an @Entity an EntityList is a collection of
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

        // Read side UNCHANGED: the managed/mapped EntityList still gets its hydration loader.
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
        // Fix-round 2 I-2: the entity-collection write cascade is gated by WRITABILITY only (child id +
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
        // generated-key coercible) and has no repository of its own. (Since Task 3 a String-id
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
    void relationFreePagedFindAllKeepsSingleConstantShape() throws Exception {
        // Legacy-parity guard for R-T11c: a descriptor WITHOUT traversable props can never produce a
        // join, so its paged findAll keeps the byte-identical single-constant emission — no
        // SQL_FIND_ALL_QUALIFIED field, no seekWhereTerms loop.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/navplain"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-navplain"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-navplain"));

        Files.writeString(sourceDir.resolve("Note.java"), """
                package navplain;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Note {
                    @Identity Long id;
                    String text;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getText() { return text; }
                    public void setText(String text) { this.text = text; }
                }
                """);
        Files.writeString(sourceDir.resolve("NoteRepository.java"), """
                package navplain;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface NoteRepository extends CrudRepository<Note, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Note.java", "NoteRepository.java");

        String impl = Files.readString(generatedDir.resolve("navplain/NoteRepositoryImpl.java"))
                .replace("\\\"", "\"").replaceAll("\\s+", " ");

        assertTrue(impl.contains("jdbcClient.sql(SQL_FIND_ALL + joins + (orderBy.length() > 0"),
                "relation-free OFFSET emission must keep the single-constant splice:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("SQL_FIND_ALL_QUALIFIED"),
                "no qualified constant for a join-impossible descriptor:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("CursorSupport") && !impl.contains("seekWhere"),
                "a Page-returning findAll must not emit any cursor/seek machinery:" + System.lineSeparator() + impl);
    }

    @Test
    void pagedSortThroughEntityListOrdersByAggregateSortTermAndIdTiebreak() throws Exception {
        // Task 6 (S-2 part 2): the findAllPage runtime sort through a to-many head must NOT splice a
        // fanning LEFT JOIN. Its ORDER BY term renders through the descriptor-driven aggregate
        // subquery helper, the query picks the ROOT-QUALIFIED plain select (no join), and the keyset
        // grows with the root-qualified "id" ASC tiebreak in ORDER BY, cols/dirs/props and the refs
        // the seek-terms loop walks. Cursor keys move to the descriptor-driven cursorFor overload.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/t6rt"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-t6rt"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-t6rt"));
        Files.writeString(sourceDir.resolve("WorkExperience.java"), """
                package t6rt;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class WorkExperience {
                    @Identity Long id;
                    String company;
                    Profile profile;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getCompany() { return company; }
                    public void setCompany(String company) { this.company = company; }
                    public Profile getProfile() { return profile; }
                    public void setProfile(Profile profile) { this.profile = profile; }
                }
                """);
        Files.writeString(sourceDir.resolve("Profile.java"), """
                package t6rt;
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
        Files.writeString(sourceDir.resolve("Profiles.java"), """
                package t6rt;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Profiles extends CrudRepository<Profile, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "WorkExperience.java", "Profile.java", "Profiles.java");

        String impl = sqlFlat(generatedDir.resolve("t6rt/ProfilesImpl.java"));
        assertTrue(impl.contains("orderBy.append(sql.isToManyPath(s.property())"
                        + " ? sql.aggregateSortTerm(r, s.isAscending())"
                        + " : sql.qualify(r))"),
                "to-many sort heads must switch the ORDER BY term to the aggregate sort term:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("boolean keyed = false;"),
                "the keyset flag must exist:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(((joins.isEmpty() && !keyed ? SQL_FIND_ALL"
                        + " : SQL_FIND_ALL_QUALIFIED) + joins + (orderBy.length() > 0"),
                "OFFSET must take the qualified plain select when keyed even with empty joins:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("String joins = sql.joinClause(joinRefs)"),
                "the joinClause call must stay as emitted (to-many refs are skipped by the clause itself):"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("LEFT JOIN \"work_experience\""),
                "the generated file must not splice the to-many table as a join:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void pagedFinderSortThroughElementListDescendsToMaxSubqueryWithIdTiebreak() throws Exception {
        // Task 6: the compile-time sort side (a CursoredPage finder under @OrderBy) resolves a to-many
        // head to the SAME pre-rendered aggregate term used verbatim in ORDER BY and seekWhereTerms,
        // grows props/dirs/cols with the id tiebreak, and switches the cursor keys to the descriptor
        // overload over the grown lists.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/t6el"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-t6el"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-t6el"));
        Files.writeString(sourceDir.resolve("Contact.java"), """
                package t6el;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Contact {
                    String kind;
                    String value;
                    public String getKind() { return kind; }
                    public void setKind(String kind) { this.kind = kind; }
                    public String getValue() { return value; }
                    public void setValue(String value) { this.value = value; }
                }
                """);
        Files.writeString(sourceDir.resolve("Profile.java"), """
                package t6el;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @AggregateRoot
                public class Profile {
                    @Identity Long id;
                    String fullName;
                    List<Contact> contacts = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getFullName() { return fullName; }
                    public void setFullName(String fullName) { this.fullName = fullName; }
                    public List<Contact> getContacts() { return contacts; }
                    public void setContacts(List<Contact> contacts) { this.contacts = contacts; }
                }
                """);
        Files.writeString(sourceDir.resolve("Profiles.java"), """
                package t6el;
                import jakarta.data.page.CursoredPage;
                import jakarta.data.page.PageRequest;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.OrderBy;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Profiles extends CrudRepository<Profile, Long> {
                    @OrderBy(value = "contacts.value", descending = true)
                    CursoredPage<Profile> findBy(PageRequest pageRequest);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Contact.java", "Profile.java", "Profiles.java");

        String impl = sqlFlat(generatedDir.resolve("t6el/ProfilesImpl.java"));
        assertTrue(impl.contains("ORDER BY (SELECT MAX(\"contacts\".\"value\") FROM"
                        + " \"profile_contacts\" \"contacts\" WHERE \"contacts\".\"profile_id\" ="
                        + " \"profile\".\"id\") DESC, \"profile\".\"id\" ASC"),
                "the paged finder ORDER BY must be the MAX subquery term plus the id tiebreak:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("SQL_FIND_BY_SEEK_NEXT = \"SELECT \"id\", \"full_name\" FROM \"profile\""
                        + " WHERE (((SELECT MAX("),
                "the forward seek constant must bake the aggregate term into its WHERE clause:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("IS NULL AND :cursor0 IS NULL)) AND \"profile\".\"id\" > :cursor1))"),
                "the composite seek must pin the preceding key with NULL-safe equality before the id tiebreak:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("CursorKeys.forEntity(java.util.List.of(\"contacts.value\", \"id\")"
                        + ", java.util.List.of(false, true), ProfileMetadata.INSTANCE, e)"),
                "cursor keys must ride the grown props/dirs lists through the descriptor overload:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("LEFT JOIN"),
                "no to-many sort join may be spliced anywhere in this emission:" + System.lineSeparator() + impl);
    }

    @Test
    void pagedSortThroughReferenceStillJoinsByteIdentical() throws Exception {
        // Byte-parity pin (note 4): a to-one sort must NOT gain the aggregate switch or the id
        // tiebreak at the compile-time paged emission - the legacy resolveColumn/orderBy/seekWhere
        // strings stay exactly as Task 11 shipped them (the runtime to-one parity lives in
        // dynamicSortResolvesThroughDescriptorAndContributesJoins, which this task must not break).
        Path sourceDir = Files.createDirectories(workspace.resolve("src/t6one"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-t6one"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-t6one"));
        writeRelationFixture(sourceDir);
        Files.writeString(sourceDir.resolve("ParityOrd.java"), """
                package relpath;
                import jakarta.data.page.CursoredPage;
                import jakarta.data.page.PageRequest;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.OrderBy;
                import jakarta.data.repository.Repository;
                @Repository
                public interface ParityOrd extends CrudRepository<Order, Long> {
                    @OrderBy(value = "customer")
                    CursoredPage<Order> findBy(PageRequest pageRequest);
                }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java",
                "ParityOrd.java");

        String impl = sqlFlat(generatedDir.resolve("relpath/ParityOrdImpl.java"));
        assertTrue(impl.contains("ORDER BY \"customer_id\" ASC"),
                "the to-one fk sort must keep the legacy orderBy term:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("customer_id\" ASC, \"") && !impl.contains("customer_id\" ASC, "),
                "no term may follow the legacy to-one ORDER BY (no tiebreak gained):"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("SQL_FIND_BY_SEEK_NEXT = \"SELECT \"id\", \"status\", \"customer_id\""
                        + " FROM \"order\" WHERE ((\"customer_id\" > :cursor0)) ORDER BY \"customer_id\" ASC LIMIT \""),
                "the forward seek constant must bake the legacy bare-column seek clause:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("ORDER BY (SELECT"),
                "no aggregate sort term may appear for a to-one sort:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("CursorKeys.forEntity(java.util.List.of(\"customer\"),"
                        + " java.util.List.of(true), OrderMetadata.INSTANCE, e)"),
                "cursor keys keep the UNGROWN props list on the descriptor overload:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void localSortStaysLegacyByteIdentical() throws Exception {
        // Byte-parity pin (note 4): with NO traversable prop at all the whole paged findAll stays the
        // single-constant legacy emission - no qualified constant, no keyed machinery, no terms.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/t6loc"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-t6loc"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-t6loc"));
        Files.writeString(sourceDir.resolve("Memo.java"), """
                package t6loc;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Memo {
                    @Identity Long id;
                    String text;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getText() { return text; }
                    public void setText(String text) { this.text = text; }
                }
                """);
        Files.writeString(sourceDir.resolve("Memos.java"), """
                package t6loc;
                import jakarta.data.page.CursoredPage;
                import jakarta.data.page.PageRequest;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.OrderBy;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Memos extends CrudRepository<Memo, Long> {
                    @OrderBy(value = "text")
                    CursoredPage<Memo> findBy(PageRequest pageRequest);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Memo.java", "Memos.java");

        String impl = sqlFlat(generatedDir.resolve("t6loc/MemosImpl.java"));
        assertTrue(!impl.contains("SQL_FIND_ALL_QUALIFIED") && !impl.contains("keyed"),
                "a traversal-impossible descriptor must keep the single-constant legacy shape:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(SQL_FIND_ALL + joins + (orderBy.length() > 0"),
                "the legacy OFFSET splice must stay:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("(SELECT") && !impl.contains("CursorSupport"),
                "no aggregate term and no runtime cursor helper for local sorts:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("ORDER BY \"text\" ASC"),
                "legacy orderBy term for a local sort:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("SQL_FIND_BY_SEEK_NEXT = \"SELECT \"id\", \"text\" FROM \"memo\""
                        + " WHERE ((\"text\" > :cursor0)) ORDER BY \"text\" ASC LIMIT \""),
                "the forward seek constant must bake the legacy local-sort seek clause:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("CursorKeys.forEntity(java.util.List.of(\"text\"),"
                        + " java.util.List.of(true), MemoMetadata.INSTANCE, e)"),
                "cursored-finder cursor keys ride the descriptor overload:" + System.lineSeparator() + impl);
    }

    @Test
    void generatesRepositoryQueriesWithNestedQueryDefinitions() throws Exception {
        // Plan E (E2): query-by-names methods publish their logical structure as a generated
        // <Repo>Queries. Assert the shape, the logical predicate, the paging mode and the cursor
        // tiebreak, plus the overload suffix. The <Repo>Impl bytes are unchanged in this task.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/qmd"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-qmd"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-qmd"));

        Files.writeString(sourceDir.resolve("Widget.java"), """
                package qmd;

                public class Widget {
                    public Long id;
                    public String status;

                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getStatus() { return status; }
                    public void setStatus(String status) { this.status = status; }
                }
                """);
        Files.writeString(sourceDir.resolve("Widgets.java"), """
                package qmd;

                import jakarta.data.page.Page;
                import jakarta.data.page.PageRequest;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;

                @Repository
                public interface Widgets extends CrudRepository<Widget, Long> {
                    List<Widget> findByStatus(String status);
                    long countByStatus(String status);
                    Page<Widget> findByStatus(String status, PageRequest pageRequest);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Widget.java", "Widgets.java");

        Path queriesFile = generatedDir.resolve("qmd/WidgetsQueries.java");
        assertTrue(Files.exists(queriesFile), "expected generated " + queriesFile);
        String queries = Files.readString(queriesFile).replaceAll("\\s+", " ");

        assertTrue(queries.contains("public final class WidgetsQueries"),
                "holder:" + System.lineSeparator() + queries);
        // one nested query definition per query-by-names method, in declaration order, overload suffixed
        assertTrue(queries.contains("public static final class FindByStatusQuery implements QueryDefinition"),
                "find query definition:" + System.lineSeparator() + queries);
        assertTrue(queries.contains("public static final class FindByStatusQuery2 implements QueryDefinition"),
                "paged overload must be suffixed:" + System.lineSeparator() + queries);
        assertTrue(queries.contains("public static final class CountByStatusQuery implements QueryDefinition"),
                "count query definition:" + System.lineSeparator() + queries);
        assertTrue(queries.contains("public static final FindByStatusQuery INSTANCE = new FindByStatusQuery();"),
                "INSTANCE constant:" + System.lineSeparator() + queries);
        // query metadata retains logical paths; SqlBuilder resolves them against the schema.
        assertTrue(queries.contains("Predicate.of(\"status\", Operator.EQ, \"status\", Connector.AND, false)"),
                "logical predicate:" + System.lineSeparator() + queries);
        assertTrue(queries.contains("return Operation.FIND;"), "operation:" + System.lineSeparator() + queries);
        assertTrue(queries.contains("return Operation.COUNT;"), "count operation:"
                + System.lineSeparator() + queries);
        assertTrue(queries.contains("return ResultKind.PAGE;"), "paged result kind:"
                + System.lineSeparator() + queries);
        assertTrue(queries.contains("return PagingMode.OFFSET;"), "paged mode:"
                + System.lineSeparator() + queries);
        assertTrue(queries.contains("return PagingMode.NONE;"), "plain mode:" + System.lineSeparator() + queries);
        assertTrue(queries.contains("new ParamBinding(0, \"status\")"), "param binding:"
                + System.lineSeparator() + queries);
        // the paged model declares no ORDER BY sorts of its own here, and its seek terms are the
        // id-ascending default the runtime cursor uses
        assertTrue(queries.contains("new SeekTerm(")
                        && queries.contains("new ColumnRef(\"id\", null, null, List.of()), true)"),
                "default cursor seek term:" + System.lineSeparator() + queries);
        // the impl must not have changed shape in this task
        String impl = Files.readString(generatedDir.resolve("qmd/WidgetsImpl.java"));
        assertTrue(impl.contains("SQL_FIND_BY_STATUS"), "impl constants survive:"
                + System.lineSeparator() + impl);
    }

    @Test
    void overriddenInheritedFinderProducesOneQueryDefinition() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/query-override"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-query-override"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-query-override"));

        Files.writeString(sourceDir.resolve("Widget.java"), """
                package queryoverride;
                public class Widget {
                    public Long id;
                    public String status;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getStatus() { return status; }
                    public void setStatus(String status) { this.status = status; }
                }
                """);
        Files.writeString(sourceDir.resolve("WidgetFinder.java"), """
                package queryoverride;
                import java.util.List;
                public interface WidgetFinder {
                    List<Widget> findByStatus(String status);
                }
                """);
        Files.writeString(sourceDir.resolve("Widgets.java"), """
                package queryoverride;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Widgets extends CrudRepository<Widget, Long>, WidgetFinder {
                    @Override
                    List<Widget> findByStatus(String status);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Widget.java", "WidgetFinder.java", "Widgets.java");

        String queries = Files.readString(generatedDir.resolve("queryoverride/WidgetsQueries.java"))
                .replaceAll("\\s+", " ");
        String impl = Files.readString(generatedDir.resolve("queryoverride/WidgetsImpl.java"))
                .replaceAll("\\s+", " ");
        assertTrue(queries.contains("class FindByStatusQuery implements QueryDefinition"), queries);
        assertTrue(!queries.contains("FindByStatusQuery2"), "override must not look like an overload: " + queries);
        assertTrue(occurrences(queries, "class FindByStatusQuery implements QueryDefinition") == 1,
                "one query definition per effective method: " + queries);
        assertTrue(occurrences(impl, "List<Widget> findByStatus(String status)") == 1,
                "one implementation method per effective method: " + impl);
    }

    private static int occurrences(String text, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }

    /**
     * Writes the shared {@code relpath} fixture: {@code Customer} / {@code OrderItem} / {@code Order}
     * (scalar {@code status}, {@code @Association Customer customer}, {@code List<OrderItem> orderItems}
     * element collection) plus one repository file per repository interface actually compiled by a test.
     * {@code Country}/{@code Region} exist so a multi-hop traversal ({@code customer.country.region.name})
     * reaches a genuine relation-on-relation path, and remain OUT of every repository's compiled file
     * list unless the test needs them.
     */
    private void writeRelationFixture(Path sourceDir) throws Exception {
        Files.writeString(sourceDir.resolve("Customer.java"), """
                package relpath;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Association;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Customer {
                    @Identity Long id;
                    String name;
                    @Association Country country;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public Country getCountry() { return country; }
                    public void setCountry(Country country) { this.country = country; }
                }
                """);
        Files.writeString(sourceDir.resolve("Country.java"), """
                package relpath;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Association;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Country {
                    @Identity Long id;
                    String code;
                    @Association Region region;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getCode() { return code; }
                    public void setCode(String code) { this.code = code; }
                    public Region getRegion() { return region; }
                    public void setRegion(Region region) { this.region = region; }
                }
                """);
        Files.writeString(sourceDir.resolve("Region.java"), """
                package relpath;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Region {
                    @Identity Long id;
                    String name;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        Files.writeString(sourceDir.resolve("OrderItem.java"), """
                package relpath;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class OrderItem {
                    int quantity;
                    String sku;
                    public int getQuantity() { return quantity; }
                    public void setQuantity(int quantity) { this.quantity = quantity; }
                    public String getSku() { return sku; }
                    public void setSku(String sku) { this.sku = sku; }
                }
                """);
        Files.writeString(sourceDir.resolve("Order.java"), """
                package relpath;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Association;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @AggregateRoot
                public class Order {
                    @Identity Long id;
                    String status;
                    @Association Customer customer;
                    List<OrderItem> orderItems = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getStatus() { return status; }
                    public void setStatus(String status) { this.status = status; }
                    public Customer getCustomer() { return customer; }
                    public void setCustomer(Customer customer) { this.customer = customer; }
                    public List<OrderItem> getOrderItems() { return orderItems; }
                    public void setOrderItems(List<OrderItem> orderItems) { this.orderItems = orderItems; }
                }
                """);
    }

    /** Normalises a generated source for SQL-constant pins: unescapes quoted string literals and
     *  folds whitespace so a JavaPoet soft wrap cannot break the pin. */
    private static String sqlFlat(Path generatedFile) throws Exception {
        return Files.readString(generatedFile).replace("\\\"", "\"").replaceAll("\\s+", " ");
    }

    @Test
    void methodNamePredicateThroughElementCollectionEmitsExists() throws Exception {
        // Task 8: findByOrderItemsQuantityGreaterThan — the qbn property is the concatenated camel
        // "orderItemsQuantity"; resolveCompilePath splits it at hump boundaries into the element-list
        // head "orderItems" + leaf "quantity", and the emitted SQL constant carries the EXISTS semi-join the
        // traversal needs (Task 5: a to-many head must never fan out into a LEFT JOIN). Param binding still uses the JAVA PARAMETER NAME.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-el"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-el"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-el"));
        writeRelationFixture(sourceDir);
        Files.writeString(sourceDir.resolve("Orders.java"), """
                package relpath;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.stream.Stream;
                @Repository
                public interface Orders extends CrudRepository<Order, Long> {
                    Stream<Order> findByOrderItemsQuantityGreaterThan(int quantity);
                }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java", "Orders.java");

        // The pin uses the framework's child-table naming: Names.childTable("order", "orderItems")
        // = "order_order_items" (the brief's "order_items" omitted the parent prefix). Task 5: the
        // to-many head renders an EXISTS semi-join, not a LEFT JOIN. sqlFlat folds whitespace so a
        // JavaPoet soft wrap cannot break the byte pin.
        String impl = sqlFlat(generatedDir.resolve("relpath/OrdersImpl.java"));
        assertTrue(impl.contains("FROM \"order\" WHERE EXISTS (SELECT 1 FROM \"order_order_items\" "
                        + "\"orderItems\" WHERE \"orderItems\".\"order_id\" = \"order\".\"id\" "
                        + "AND \"orderItems\".\"quantity\" > :quantity)"),
                "element-collection predicate must render EXISTS with the aliased child table:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("LEFT JOIN"),
                "a to-many predicate must NOT splice a LEFT JOIN:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"quantity\", quantity)"),
                "the bound parameter must stay the JAVA PARAMETER NAME:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("SQL_FIND_BY_ORDER_ITEMS_QUANTITY_GREATER_THAN"),
                "constant name should follow the method name:" + System.lineSeparator() + impl);
    }

    @Test
    void annotatedPredicateThroughAssociationEmitsLeftJoin() throws Exception {
        // @By("customer.name") → Reference head "customer": the join ON clause binds the root's fk
        // column to the target's id column ("order"."customer_id" = "customer"."id") and the WHERE
        // column is alias-qualified. The traversal is declared by @By, not read off the method name:
        // @Find is annotation-driven, so the name is never analyzed.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-ref"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-ref"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-ref"));
        writeRelationFixture(sourceDir);
        Files.writeString(sourceDir.resolve("Ord.java"), """
                package relpath;
                import jakarta.data.repository.By;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Find;
                import jakarta.data.repository.Repository;
                import java.util.stream.Stream;
                @Repository
                public interface Ord extends CrudRepository<Order, Long> {
                    @Find
                    Stream<Order> findByCustomerName(@By("customer.name") String name);
                }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java", "Ord.java");

        String impl = sqlFlat(generatedDir.resolve("relpath/OrdImpl.java"));
        assertTrue(impl.contains("FROM \"order\" LEFT JOIN \"customer\" AS \"customer\" ON \"order\".\"customer_id\" = \"customer\".\"id\" WHERE \"customer\".\"name\" = :name"),
                "@Association predicate must emit the fk-target-id LEFT JOIN and alias-qualified WHERE:"
                        + System.lineSeparator() + impl);
    }

    /**
     * Writes the Task 5 EntityList fixture (package {@code exa}): {@code Order} is an
     * {@code @AggregateRoot} carrying {@code List<Line> lines}; {@code Line} is a MANAGED
     * {@code @Entity} (its own {@code LineRepository}, Decision 7) with {@code String product} and
     * a back-reference {@code Order order}, so the one-to-many inverse resolves to the fk column
     * {@code "order_id"} on the target table {@code "line"}.
     */
    private void writeLinesFixture(Path sourceDir) throws Exception {
        Files.writeString(sourceDir.resolve("Line.java"), """
                package exa;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class Line {
                    @Identity Long id;
                    String product;
                    Order order;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getProduct() { return product; }
                    public void setProduct(String product) { this.product = product; }
                    public Order getOrder() { return order; }
                    public void setOrder(Order order) { this.order = order; }
                }
                """);
        Files.writeString(sourceDir.resolve("Order.java"), """
                package exa;
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
        Files.writeString(sourceDir.resolve("LineRepository.java"), """
                package exa;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface LineRepository extends CrudRepository<Line, Long> { }
                """);
    }

    @Test
    void predicateThroughEntityListRendersExistsNotJoin() throws Exception {
        // Task 5: findByLinesProduct - the head "lines" is a Prop.EntityList (to-many). The predicate
        // renders as an EXISTS semi-join over the target table, aliased by the relation PROPERTY name,
        // and the statement carries NO LEFT JOIN, so the root row cannot fan out. The bound parameter
        // stays the JAVA PARAMETER NAME ("product"), exactly like the join version rendered.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-exa"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-exa"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-exa"));
        writeLinesFixture(sourceDir);
        Files.writeString(sourceDir.resolve("Orders.java"), """
                package exa;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Orders extends CrudRepository<Order, Long> {
                    List<Order> findByLinesProduct(String product);
                }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Line.java", "Order.java", "LineRepository.java", "Orders.java");

        String impl = sqlFlat(generatedDir.resolve("exa/OrdersImpl.java"));
        assertTrue(impl.contains("SQL_FIND_BY_LINES_PRODUCT"),
                "constant name should follow the method name:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("EXISTS (SELECT 1 FROM \"line\" \"lines\" WHERE \"lines\".\"order_id\" = \"order\".\"id\" AND \"lines\".\"product\" = :product)"),
                "entity-list predicate must render EXISTS, alias-qualified by the relation property:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("LEFT JOIN"),
                "a to-many predicate must NOT splice a LEFT JOIN:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"product\", product)"),
                "the bound parameter must stay the JAVA PARAMETER NAME:" + System.lineSeparator() + impl);
    }
    @Test
    void qualifiedRootSelectParityGuardPinsBothHelperCallShapes() throws Exception {
        // S-1 parity guard. The SQL_FIND_ALL_QUALIFIED emission and the joined-find branch now
        // render through ONE SqlBuilder.qualifiedRootSelect helper. The two intentional modes of that
        // helper are both unpinned by the existing suite for THIS descriptor: the EXISTS-only find (no
        // join spliced) keeps the BARE root projection, while the same descriptor's qualified findAll
        // constant lists every root column as \"order\".\"col\". Locked byte-for-byte here.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-par"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-par"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-par"));
        writeLinesFixture(sourceDir);
        Files.writeString(sourceDir.resolve("Orders.java"), """
                package exa;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Orders extends CrudRepository<Order, Long> {
                    List<Order> findByLinesProduct(String product);
                }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Line.java", "Order.java", "LineRepository.java", "Orders.java");

        String impl = sqlFlat(generatedDir.resolve("exa/OrdersImpl.java"));
        // Mode 1 (qualified == false, no join spliced): the bare root projection.
        assertTrue(impl.contains("SELECT \"id\", \"status\" FROM \"order\" WHERE EXISTS (SELECT 1 FROM \"line\" \"lines\" WHERE \"lines\".\"order_id\" = \"order\".\"id\" AND \"lines\".\"product\" = :product)"),
                "the no-join helper mode must keep the bare root projection:" + System.lineSeparator() + impl);
        // Mode 2 (qualified == true, no join spliced by the constant itself): the findAll constant.
        assertTrue(impl.contains("SELECT \"order\".\"id\", \"order\".\"status\" FROM \"order\""),
                "the qualified helper mode must list every root column:" + System.lineSeparator() + impl);
    }

    @Test
    void predicateThroughElementListRendersExistsNotJoin() throws Exception {
        // Task 5: findByContactsValue - the head "contacts" is a Prop.ElementList; the EXISTS renders
        // over the element child table ("profile_contacts"), aliased by the relation property name and
        // keyed by the parent fk column ("profile_id"). No LEFT JOIN anywhere.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-exe"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-exe"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-exe"));
        Files.writeString(sourceDir.resolve("Contact.java"), """
                package exv;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Contact {
                    String kind;
                    String value;
                    public String getKind() { return kind; }
                    public void setKind(String kind) { this.kind = kind; }
                    public String getValue() { return value; }
                    public void setValue(String value) { this.value = value; }
                }
                """);
        Files.writeString(sourceDir.resolve("Profile.java"), """
                package exv;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @AggregateRoot
                public class Profile {
                    @Identity Long id;
                    String fullName;
                    List<Contact> contacts = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getFullName() { return fullName; }
                    public void setFullName(String fullName) { this.fullName = fullName; }
                    public List<Contact> getContacts() { return contacts; }
                    public void setContacts(List<Contact> contacts) { this.contacts = contacts; }
                }
                """);
        Files.writeString(sourceDir.resolve("Profiles.java"), """
                package exv;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Profiles extends CrudRepository<Profile, Long> {
                    List<Profile> findByContactsValue(String contactsValue);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Contact.java", "Profile.java", "Profiles.java");

        String impl = sqlFlat(generatedDir.resolve("exv/ProfilesImpl.java"));
        assertTrue(impl.contains("EXISTS (SELECT 1 FROM \"profile_contacts\" \"contacts\" WHERE \"contacts\".\"profile_id\" = \"profile\".\"id\" AND \"contacts\".\"value\" = :contactsValue)"),
                "element-list predicate must render EXISTS over the child table:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("LEFT JOIN"),
                "a to-many predicate must NOT splice a LEFT JOIN:" + System.lineSeparator() + impl);
    }

    @Test
    void predicateThroughReferenceStillLeftJoinsWithoutExists() throws Exception {
        // Task 5 guard: a to-one (Reference) head keeps the existing LEFT JOIN splice BYTE-IDENTICAL
        // and never renders an EXISTS - the fan-out risk the EXISTS fixes exists only at to-many heads.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-exr"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-exr"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-exr"));
        writeRelationFixture(sourceDir);
        Files.writeString(sourceDir.resolve("GuardOrd.java"), """
                package relpath;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.stream.Stream;
                @Repository
                public interface GuardOrd extends CrudRepository<Order, Long> {
                    Stream<Order> findByCustomerName(String name);
                }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java", "GuardOrd.java");

        String impl = sqlFlat(generatedDir.resolve("relpath/GuardOrdImpl.java"));
        assertTrue(impl.contains("FROM \"order\" LEFT JOIN \"customer\" AS \"customer\" ON \"order\".\"customer_id\" = \"customer\".\"id\" WHERE \"customer\".\"name\" = :name"),
                "a to-one predicate must keep the byte-identical LEFT JOIN splice:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("EXISTS"),
                "a to-one predicate must NOT render an EXISTS:" + System.lineSeparator() + impl);
    }

    @Test
    void mixedToOneAndToManyPredicatesConjoinJoinAndExists() throws Exception {
        // RB3 (plan "to-many + to-one mixed paths", final-review pin): ONE finder declaring BOTH a
        // to-one predicate (Reference head "customer") and a to-many predicate (ElementList head
        // "orderItems") must render the mixed shapes CONJUNCTED - the LEFT JOIN splice for the
        // to-one traversal AND the EXISTS semi-join for the to-many traversal, AND-ed in one WHERE
        // (SqlBuilder.build emits the non-existential conjuncts first). Each shape alone is pinned
        // above; this pins their conjunction.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-mix"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-mix"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-mix"));
        writeRelationFixture(sourceDir);
        Files.writeString(sourceDir.resolve("MixedOrd.java"), """
                package relpath;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.stream.Stream;
                @Repository
                public interface MixedOrd extends CrudRepository<Order, Long> {
                    Stream<Order> findByCustomerNameAndOrderItemsQuantityGreaterThan(String name, int quantity);
                }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java", "MixedOrd.java");

        String impl = sqlFlat(generatedDir.resolve("relpath/MixedOrdImpl.java"));
        assertTrue(impl.contains("FROM \"order\" LEFT JOIN \"customer\" AS \"customer\" ON \"order\".\"customer_id\" = \"customer\".\"id\" WHERE \"customer\".\"name\" = :name AND EXISTS (SELECT 1 FROM \"order_order_items\" \"orderItems\" WHERE \"orderItems\".\"order_id\" = \"order\".\"id\" AND \"orderItems\".\"quantity\" > :quantity)"),
                "RB3: the to-one LEFT JOIN and the to-many EXISTS must BOTH appear, conjuncted with AND:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("SQL_FIND_BY_CUSTOMER_NAME_AND_ORDER_ITEMS_QUANTITY_GREATER_THAN"),
                "constant name should follow the method name:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"name\", name)"),
                "the to-one bound parameter must stay the JAVA PARAMETER NAME:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"quantity\", quantity)"),
                "the to-many bound parameter must stay the JAVA PARAMETER NAME:" + System.lineSeparator() + impl);
    }

    @Test
    void countThroughEntityListUsesExistsAndStaysUnjoinedTotal() throws Exception {
        // Task 5: countByLinesProduct keeps the COUNT(*) projection, renders the same EXISTS conjunct
        // and carries NO LEFT JOIN anywhere - the total counts ROOT rows, not root-child pairs.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-exc"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-exc"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-exc"));
        writeLinesFixture(sourceDir);
        Files.writeString(sourceDir.resolve("CountOrders.java"), """
                package exa;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface CountOrders extends CrudRepository<Order, Long> {
                    long countByLinesProduct(String product);
                }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Line.java", "Order.java", "LineRepository.java", "CountOrders.java");

        String impl = sqlFlat(generatedDir.resolve("exa/CountOrdersImpl.java"));
        assertTrue(impl.contains("SELECT COUNT(*) FROM \"order\" WHERE EXISTS (SELECT 1 FROM \"line\" \"lines\" WHERE \"lines\".\"order_id\" = \"order\".\"id\" AND \"lines\".\"product\" = :product)"),
                "countBy through an EntityList must render EXISTS on the unjoined COUNT(*) total:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("LEFT JOIN"),
                "no LEFT JOIN anywhere:" + System.lineSeparator() + impl);
    }

    @Test
    void multiHopMethodNamePredicateTraversesTwoToOneRelations() throws Exception {
        // findByCustomerCountryCode traverses TWO to-one relations (order→customer→country). The
        // resolver now chains both LEFT JOINs and qualifies the leaf by the last alias.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-mh"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-mh"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-mh"));
        writeRelationFixture(sourceDir);
        Files.writeString(sourceDir.resolve("MultiHopOrders.java"), """
                package relpath;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface MultiHopOrders extends CrudRepository<Order, Long> {
                    List<Order> findByCustomerCountryCode(String code);
                }
                """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(sourceDir, classesDir, generatedDir,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java", "MultiHopOrders.java");
        String impl = sqlFlat(generatedDir.resolve("relpath/MultiHopOrdersImpl.java"));
        assertTrue(impl.contains("FROM \"order\" LEFT JOIN \"customer\" AS \"customer\" ON \"order\".\"customer_id\" = \"customer\".\"id\""
                        + " LEFT JOIN \"country\" AS \"country\" ON \"customer\".\"country_id\" = \"country\".\"id\""
                        + " WHERE \"country\".\"code\" = :code"),
                "a two-hop to-one finder must chain both LEFT JOINs and qualify the leaf by the last alias:"
                        + System.lineSeparator() + impl);
        assertTrue(!notesOf(diagnostics).contains("traverses more than one relation"),
                "multi-hop to-one traversal must no longer be NOTE-rejected:" + System.lineSeparator() + notesOf(diagnostics));
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
    void postgreSqlDialectSelectionGeneratesPostgreSqlDialectField() throws Exception {
        // -Afluda.dialect=postgresql selects the PostgreSQL dialect: the generated impl's SQL_DIALECT
        // field references PostgreSqlDialect.INSTANCE (which today renders identically to H2).
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-pg"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-pg"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-pg"));
        Files.writeString(sourceDir.resolve("Item.java"), """
                package rppg;
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
                package rppg;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Items extends CrudRepository<Item, Long> { }
                """);

        compileWithDialect(sourceDir, classesDir, generatedDir, "postgresql", "Item.java", "Items.java");

        String impl = sqlFlat(generatedDir.resolve("rppg/ItemsImpl.java"));
        assertTrue(impl.contains("PostgreSqlDialect.INSTANCE"),
                "the generated impl must use the selected PostgreSQL dialect:" + System.lineSeparator() + impl);
    }

    @Test
    void localAndEmbeddedMethodPredicatesStayUnqualifiedByteIdentical() throws Exception {
        // Regression gate for the byte pins: predicates that resolve to LOCAL refs — including an
        // embedded leaf — must render with no join and no qualifier, exactly like today.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-local"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-local"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-local"));
        Files.writeString(sourceDir.resolve("Money.java"), """
                package rplocal;
                import io.github.fludakit.data.annotation.ValueObject;
                import java.math.BigDecimal;
                @ValueObject
                public class Money {
                    BigDecimal amount;
                    String currency;
                    public BigDecimal getAmount() { return amount; }
                    public void setAmount(BigDecimal amount) { this.amount = amount; }
                    public String getCurrency() { return currency; }
                    public void setCurrency(String currency) { this.currency = currency; }
                }
                """);
        Files.writeString(sourceDir.resolve("Invoice.java"), """
                package rplocal;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Invoice {
                    @Identity Long id;
                    String number;
                    Money money;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getNumber() { return number; }
                    public void setNumber(String number) { this.number = number; }
                    public Money getMoney() { return money; }
                    public void setMoney(Money money) { this.money = money; }
                }
                """);
        Files.writeString(sourceDir.resolve("Invoices.java"), """
                package rplocal;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Invoices extends CrudRepository<Invoice, Long> {
                    List<Invoice> findByNumber(String number);
                    long countByMoneyCurrency(String currency);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Money.java", "Invoice.java", "Invoices.java");

        String impl = sqlFlat(generatedDir.resolve("rplocal/InvoicesImpl.java"));
        assertTrue(impl.contains("SELECT \"id\", \"number\", \"money_amount\", \"money_currency\" FROM \"invoice\" WHERE \"number\" = :number"),
                "local scalar predicate must stay unqualified with no join:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("SELECT COUNT(*) FROM \"invoice\" WHERE \"money_currency\" = :currency"),
                "embedded-leaf predicate must stay unqualified with no join:" + System.lineSeparator() + impl);
    }

    @Test
    void countByThroughRelationEmitsExists() throws Exception {
        // countByOrderItemsQuantityGreaterThan: the to-many head renders EXISTS; the projection stays
        // COUNT(*) and the total counts root rows, not root-child pairs.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-count"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-count"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-count"));
        writeRelationFixture(sourceDir);
        Files.writeString(sourceDir.resolve("CountOrders.java"), """
                package relpath;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface CountOrders extends CrudRepository<Order, Long> {
                    long countByOrderItemsQuantityGreaterThan(int quantity);
                }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java", "CountOrders.java");

        String impl = sqlFlat(generatedDir.resolve("relpath/CountOrdersImpl.java"));
        assertTrue(impl.contains("SELECT COUNT(*) FROM \"order\" WHERE EXISTS (SELECT 1 FROM \"order_order_items\" \"orderItems\" WHERE \"orderItems\".\"order_id\" = \"order\".\"id\" AND \"orderItems\".\"quantity\" > :quantity)"),
                "countBy through a to-many must render EXISTS on the unjoined COUNT(*) total:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("LEFT JOIN"),
                "a to-many countBy must NOT splice a LEFT JOIN anywhere:" + System.lineSeparator() + impl);
    }

    @Test
    void deleteByThroughRelationRendersExistsSemiJoin() throws Exception {
        // C-1: a deleteBy whose predicate traverses a relation renders a correlated EXISTS semi-join
        // through SqlBuilder.build's delete mode — a joined DELETE ... LEFT JOIN is invalid SQL on
        // H2/PostgreSQL/SQL Server — and emits NO delete NOTE. The old T8-I1 NOTE-degrade to the
        // join-free legacy predicate could match the wrong rows and is removed. Guards: a LOCAL-property
        // deleteBy stays byte-identical legacy with no join, no DELETE ever carries a LEFT JOIN, and
        // find/count for the SAME traversal still emit their join (the change is delete-scoped).
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-del"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-del"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-del"));
        writeRelationFixture(sourceDir);
        Files.writeString(sourceDir.resolve("DeleteOrders.java"), """
                package relpath;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.stream.Stream;
                @Repository
                public interface DeleteOrders extends CrudRepository<Order, Long> {
                    void deleteByCustomerName(String name);
                    void deleteByStatus(String status);
                    Stream<Order> findByCustomerName(String name);
                    long countByCustomerName(String name);
                }
                """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(sourceDir, classesDir, generatedDir,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java", "DeleteOrders.java");
        // compileWithDiagnostics throws on a failed round, so reaching here proves the C-1 EXISTS
        // semi-join rendering does not abort the compile.
        Path implFile = generatedDir.resolve("relpath/DeleteOrdersImpl.java");
        assertTrue(Files.exists(implFile), "the delete-through-relation render must not abort the compile");

        String notes = notesOf(diagnostics);
        long deleteNotes = diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.NOTE)
                .filter(d -> d.getMessage(Locale.getDefault()).contains("DELETE through a relation"))
                .count();
        assertTrue(deleteNotes == 0, "a single-hop deleteBy must emit NO delete NOTE:" + System.lineSeparator() + notes);

        String impl = sqlFlat(implFile);
        assertTrue(impl.contains("SQL_DELETE_BY_CUSTOMER_NAME = \"DELETE FROM \"order\" WHERE EXISTS (SELECT 1 FROM \"customer\" \"customer\" WHERE \"customer\".\"id\" = \"order\".\"customer_id\" AND \"customer\".\"name\" = :name)\""),
                "deleteBy through a relation must render the reversed-edge EXISTS semi-join:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("DELETE FROM \"order\" LEFT JOIN"),
                "a DELETE must NEVER carry a LEFT JOIN:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("SQL_DELETE_BY_STATUS = \"DELETE FROM \"order\" WHERE \"status\" = :status\""),
                "local-property deleteBy must stay byte-identical legacy:" + System.lineSeparator() + impl);

        // (d) find AND count for the SAME customerName traversal still emit the join (fix not over-applied).
        String joinedFindWhere = "FROM \"order\" LEFT JOIN \"customer\" AS \"customer\" ON \"order\".\"customer_id\""
                + " = \"customer\".\"id\" WHERE \"customer\".\"name\" = :name";
        assertTrue(impl.contains("SELECT COUNT(*) " + joinedFindWhere),
                "countByCustomerName must keep the joined rendering:" + System.lineSeparator() + impl);
        int firstJoinFragment = impl.indexOf(joinedFindWhere);
        int secondJoinFragment = impl.indexOf(joinedFindWhere, firstJoinFragment + 1);
        assertTrue(firstJoinFragment >= 0 && secondJoinFragment > firstJoinFragment,
                "findByCustomerName AND countByCustomerName must BOTH still carry the join fragment:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void deleteByThroughElementCollectionAndMixedPredicatesRenderExists() throws Exception {
        // C-1 pins: a to-many delete renders the SAME EXISTS conjunct find/count use (delete now
        // agrees with them — gap 1's promise); a mixed to-one + local delete conjoins the bare
        // local term FIRST and the reversed-edge EXISTS second (SqlBuilder.build's two-pass conjunct
        // order); a multi-hop delete path stays NOTE-rejected by the resolver and renders neither
        // EXISTS nor a join.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-del2"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-del2"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-del2"));
        writeRelationFixture(sourceDir);
        Files.writeString(sourceDir.resolve("Delete2Orders.java"), """
                package relpath;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Delete2Orders extends CrudRepository<Order, Long> {
                    void deleteByOrderItemsQuantityGreaterThan(int quantity);
                    void deleteByCustomerNameAndStatus(String name, String status);
                    void deleteByCustomerCountryCode(String code);
                }
                """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(sourceDir, classesDir, generatedDir,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java", "Delete2Orders.java");
        String impl = sqlFlat(generatedDir.resolve("relpath/Delete2OrdersImpl.java"));

        assertTrue(impl.contains("DELETE FROM \"order\" WHERE EXISTS (SELECT 1 FROM \"order_order_items\" \"orderItems\" WHERE \"orderItems\".\"order_id\" = \"order\".\"id\" AND \"orderItems\".\"quantity\" > :quantity)"),
                "deleteBy through a to-many must render the to-many EXISTS conjunct:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("DELETE FROM \"order\" WHERE \"status\" = :status AND EXISTS (SELECT 1 FROM \"customer\" \"customer\" WHERE \"customer\".\"id\" = \"order\".\"customer_id\" AND \"customer\".\"name\" = :name)"),
                "mixed delete: bare local conjunct first, reversed-edge EXISTS conjunct second:" + System.lineSeparator() + impl);
        String notes = notesOf(diagnostics);
        assertTrue(notes.contains("path 'customerCountryCode' traverses more than one relation; not supported"),
                "the multi-hop delete path must keep the verbatim resolver NOTE:" + System.lineSeparator() + notes);
        assertTrue(!impl.contains("EXISTS (SELECT 1 FROM \"country\"") && !impl.contains("LEFT JOIN"),
                "a rejected multi-hop delete renders neither EXISTS nor a join:" + System.lineSeparator() + impl);
        assertTrue(!notes.contains("DELETE through a relation"),
                "the T8-I1 delete-degrade NOTE text is gone:" + System.lineSeparator() + notes);
    }

    @Test
    void associationFieldMaterializesIdOnlyPartialWhileCompositionKeepsLoader() throws Exception {
        // Task 9: an @Association field (composition=false) materializes at mapper-read time as an
        // ID-ONLY partial instance through a generated partial_<Target> helper, with NO two-phase
        // loader. A bare entity field on an @AggregateRoot (composition=true) keeps its two-phase
        // hydrate_<field> loader exactly as before. The write path binds only the association id, so a
        // partial (id set, other leaves null) round-trips without reading non-id leaves.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/assoc"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-assoc"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-assoc"));

        Files.writeString(sourceDir.resolve("Customer.java"), """
                package assoc;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Customer {
                    @Identity Long id;
                    String name;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        Files.writeString(sourceDir.resolve("Payment.java"), """
                package assoc;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Payment {
                    @Identity Long id;
                    long amount;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public long getAmount() { return amount; }
                    public void setAmount(long amount) { this.amount = amount; }
                }
                """);
        Files.writeString(sourceDir.resolve("Order.java"), """
                package assoc;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Association;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Order {
                    @Identity Long id;
                    String status;
                    @Association Customer customer;
                    Payment payment;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getStatus() { return status; }
                    public void setStatus(String status) { this.status = status; }
                    public Customer getCustomer() { return customer; }
                    public void setCustomer(Customer customer) { this.customer = customer; }
                    public Payment getPayment() { return payment; }
                    public void setPayment(Payment payment) { this.payment = payment; }
                }
                """);
        Files.writeString(sourceDir.resolve("Orders.java"), """
                package assoc;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Orders extends CrudRepository<Order, Long> { }
                """);
        // Payment has its own repository → it is a MANAGED target, so the composition (Order.payment)
        // keeps its two-phase hydrate loader. Customer has no repository, which is irrelevant to the
        // association partial (it only needs a no-arg ctor + setId).
        Files.writeString(sourceDir.resolve("Payments.java"), """
                package assoc;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Payments extends CrudRepository<Payment, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Customer.java", "Payment.java", "Order.java", "Orders.java", "Payments.java");

        String mapper = Files.readString(generatedDir.resolve("assoc/OrderRowMapper.java"));
        assertTrue(mapper.contains("Long customer_id = rs.getObject(OrderMetadata.CUSTOMER, Long.class);"),
                "the association fk must be read into a local by the owner metadata constant:"
                        + System.lineSeparator() + mapper);
        assertTrue(mapper.contains("entity.setCustomer(customer_id == null ? null : partial_Customer(customer_id));"),
                "the association must materialize as an id-only partial through the ternary:"
                        + System.lineSeparator() + mapper);
        assertTrue(mapper.contains("private static Customer partial_Customer(Long id)"),
                "missing the generated partial_Customer helper:" + System.lineSeparator() + mapper);
        assertTrue(mapper.contains("Customer c = new Customer();") && mapper.contains("c.setId(id);")
                        && mapper.contains("return c;"),
                "the partial helper must be new + setId(id) + return, reflection-free:" + System.lineSeparator() + mapper);
        // The composition field (payment) is NOT read by the mapper — the loader fills it.
        assertTrue(!mapper.contains("partial_Payment") && !mapper.contains("setPayment"),
                "a composition reference is never read by the row mapper (loader-fills):" + System.lineSeparator() + mapper);

        String impl = Files.readString(generatedDir.resolve("assoc/OrdersImpl.java")).replace("\\\"", "\"");
        // Composition keeps its two-phase loader.
        assertTrue(impl.contains("private void hydratePayment(Collection<Order> parents)"),
                "the composition target must keep its many-to-one loader:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("hydrateAll") && impl.contains("hydratePayment(parents)"),
                "the umbrella must dispatch the composition loader:" + System.lineSeparator() + impl);
        // Association gets NO loader.
        assertTrue(!impl.contains("hydrateCustomer"),
                "an @Association must NOT get a two-phase loader:" + System.lineSeparator() + impl);
        // Write path binds ONLY the association id (never a non-id leaf), so partials round-trip.
        assertTrue(impl.contains(".param(\"customer\", entity.getCustomer() == null ? null : entity.getCustomer().getId())"),
                "the write path must bind the association id through boundValue:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("entity.getCustomer().get") || impl.contains("entity.getCustomer().getId()"),
                "the generated save path must bind only the association id, never a non-id leaf:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void associationTargetWithoutPartialConstructorDegradesToNullWithNote() throws Exception {
        // Requirements Lock NOTE-degrade: if the association target lacks a no-arg ctor or setId, the
        // mapper leaves the field null (no partial, no loader) and a NOTE is recorded — never an abort.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/assocn"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-assocn"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-assocn"));

        Files.writeString(sourceDir.resolve("Locked.java"), """
                package assocn;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Locked {
                    @Identity Long id;
                    public Locked(Long id) { this.id = id; }
                    public Long getId() { return id; }
                }
                """);
        Files.writeString(sourceDir.resolve("Holder.java"), """
                package assocn;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Association;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Holder {
                    @Identity Long id;
                    @Association Locked locked;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Locked getLocked() { return locked; }
                    public void setLocked(Locked locked) { this.locked = locked; }
                }
                """);
        Files.writeString(sourceDir.resolve("Holders.java"), """
                package assocn;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Holders extends CrudRepository<Holder, Long> { }
                """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(
                sourceDir, classesDir, generatedDir, "Locked.java", "Holder.java", "Holders.java");

        String notes = notesOf(diagnostics);
        assertTrue(notes.contains("association 'locked' on assocn.Holder"),
                "the missing-partial-ctor NOTE must name the association:" + System.lineSeparator() + notes);
        String mapper = Files.readString(generatedDir.resolve("assocn/HolderRowMapper.java"));
        assertTrue(!mapper.contains("partial_Locked") && !mapper.contains("setLocked"),
                "a NOTE-degraded association must stay null (no partial, no setter call):"
                        + System.lineSeparator() + mapper);
    }

    @Test
    void associationPredicateTraversalMaterializesFullJoinedInstance() throws Exception {
        // Join-returns-all-fields: findByCustomerName traverses the @Association customer Reference at
        // compile time (Task 8), so the SELECT carries the target's flattened columns aliased to
        // customer_join_*, and the method uses a generated mapRow_full_<method> mapper that sets
        // customer to a FULLY populated instance read from the aliased join columns.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-full"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-full"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-full"));
        writeRelationFixture(sourceDir);
        Files.writeString(sourceDir.resolve("FullOrd.java"), """
                package relpath;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface FullOrd extends CrudRepository<Order, Long> {
                    List<Order> findByCustomerName(String name);
                }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java", "FullOrd.java");

        String impl = sqlFlat(generatedDir.resolve("relpath/FullOrdImpl.java"));
        // Root columns qualified by the root table, joined target columns qualified + aliased.
        assertTrue(impl.contains("SELECT \"order\".\"id\", \"order\".\"status\", \"order\".\"customer_id\", "
                        + "\"customer\".\"id\" AS \"customer_join_id\", \"customer\".\"name\" AS \"customer_join_name\" "
                        + "FROM \"order\""),
                "the joined find must project the target's columns under <alias>_join_<col>:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("mapRow_full_find_by_customer_name"),
                "the method must carry a generated per-method full mapper:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".query((rs, rowNum) -> mapRow_full_find_by_customer_name(rs, rowNum))"),
                "the query call-site must use the full mapper:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("Customer customerFull = new Customer();")
                        && impl.contains("customerFull.setId(customer_join_id)")
                        && impl.contains("customerFull.setName(rs.getObject(\"customer_join_name\", String.class))")
                        && impl.contains("entity.setCustomer(customerFull)"),
                "the full mapper must build a fully-populated target from the aliased labels:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void pagedFinderWithAssociationTraversalDegradesWithNote() throws Exception {
        // Task 9 degrade: a Page-returning finder whose method-name predicate path traverses the
        // @Association customer Reference at compile time CANNOT carry the full joined materialisation
        // (the runtime sort/seek wiring appends its own join chain to the base constant), so it is
        // degraded to the normal base row mapper — association fields stay id-only partials — and the
        // processor records a NOTE naming the degrade. Pin the NOTE text and the absence of the
        // per-method full mapper for the paged shape.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-pgfull"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-pgfull"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-pgfull"));
        writeRelationFixture(sourceDir);
        Files.writeString(sourceDir.resolve("PagedOrd.java"), """
                package relpath;
                import jakarta.data.page.Page;
                import jakarta.data.page.PageRequest;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface PagedOrd extends CrudRepository<Order, Long> {
                    Page<Order> findByCustomerName(PageRequest pageRequest, String name);
                }
                """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(sourceDir,
                classesDir, generatedDir,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java", "PagedOrd.java");

        String notes = notesOf(diagnostics);
        assertTrue(notes.contains("full materialisation through joins is not supported for paged finders"),
                "the paged joined-full degrade must record its NOTE:" + System.lineSeparator() + notes);

        String impl = sqlFlat(generatedDir.resolve("relpath/PagedOrdImpl.java"));
        assertTrue(!impl.contains("mapRow_full_find_by_customer_name"),
                "the degraded paged finder must NOT carry a generated full mapper:" + System.lineSeparator() + impl);
    }

    private void compile(Path sourceDir, Path classesDir, Path generatedDir, String... fileNames) throws Exception {
        compileWithDiagnostics(sourceDir, classesDir, generatedDir, fileNames);
    }

    /** The result of running the javac task: whole-round success plus every collected diagnostic. */
    private record TaskResult(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) { }

    private TaskResult runTask(Path sourceDir, Path classesDir, Path generatedDir, String... fileNames) throws Exception {
        return runTask(sourceDir, classesDir, generatedDir, List.of(), fileNames);
    }

    private TaskResult runTask(Path sourceDir, Path classesDir, Path generatedDir,
                               List<String> processorOptions, String... fileNames) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A system Java compiler is required to run this test");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            java.util.List<java.io.File> files = new java.util.ArrayList<>();
            for (String n : fileNames) files.add(sourceDir.resolve(n).toFile());
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(files.toArray(new java.io.File[0]));
            List<String> options = new java.util.ArrayList<>(List.of(
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", classesDir.toString(),
                    "-s", generatedDir.toString(),
                    "-processor", "io.github.fludakit.data.processor.RepositoryProcessor"));
            options.addAll(processorOptions);
            boolean success = compiler.getTask(null, fm, diagnostics, options, null, units).call();
            return new TaskResult(success, diagnostics.getDiagnostics());
        }
    }

    private void compileWithDialect(Path sourceDir, Path classesDir, Path generatedDir,
                                    String dialect, String... fileNames) throws Exception {
        TaskResult result = runTask(sourceDir, classesDir, generatedDir,
                List.of("-Afluda.dialect=" + dialect), fileNames);
        String messages = result.diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(Object::toString).collect(Collectors.joining(System.lineSeparator()));
        assertTrue(result.success(), "Annotation processing failed:" + System.lineSeparator() + messages);
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
        assertTrue(order.contains("public Object navigate(String path, Order root)"),
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
        assertTrue(order.contains("default: throw new IllegalArgumentException(\"Unknown property path '\""
                        + " + path + \"' on Order\")"),
                "an unknown path must fail fast naming the path and the entity:" + System.lineSeparator() + order);
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
    void nonPagedSortThroughElementListRendersAggregateAndNoTiebreak() throws Exception {
        // C-4.1/C-4.2 (plan B parked minor): the NON-paged SqlBuilder.build emission of a to-many
        // sort head renders the same aggregate term verbatim in ORDER BY, splices NO LEFT JOIN for
        // the collection, and gains NO root-id tiebreak — tiebreaking is a paged-cursor concern (seek
        // keys need a total order); findTop… through a collection stays order-nondeterministic among
        // ties like any non-paged ORDER BY.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/c4sort"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-c4sort"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-c4sort"));
        writeRelationFixture(sourceDir);
        Files.writeString(sourceDir.resolve("SortOrd.java"), """
                package relpath;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.OrderBy;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface SortOrd extends CrudRepository<Order, Long> {
                    @OrderBy(value = "orderItems.quantity", descending = true)
                    List<Order> findByStatus(String status);
                }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java", "SortOrd.java");

        String impl = sqlFlat(generatedDir.resolve("relpath/SortOrdImpl.java"));
        assertTrue(impl.contains("ORDER BY (SELECT MAX(\"orderItems\".\"quantity\") FROM \"order_order_items\" \"orderItems\" WHERE \"orderItems\".\"order_id\" = \"order\".\"id\") DESC"),
                "the non-paged ORDER BY must carry the aggregate term verbatim:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("LEFT JOIN"),
                "no to-many join may be spliced by the non-paged emission:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains(") DESC, \"order\".\"id\"") && !impl.contains(" DESC, \"order\".\"id\" ASC"),
                "non-paged finders gain no root-id tiebreak (policy: tiebreaks exist for cursor seek keys only):"
                        + System.lineSeparator() + impl);
    }

}
