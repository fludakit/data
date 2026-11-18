package io.github.fludakit.data.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static io.github.fludakit.data.processor.ProcessorHarness.TaskResult;
import static io.github.fludakit.data.processor.ProcessorHarness.compile;
import static io.github.fludakit.data.processor.ProcessorHarness.runTask;
import static io.github.fludakit.data.processor.ProcessorHarness.sqlFlat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jakarta Data allows omitting {@code @By}/{@code @Param} when parameter names match attributes
 * / named SQL parameters and the compile uses {@code -parameters} (see issue #51).
 */
class ParameterNameDefaultsTest {

    @TempDir
    Path workspace;

    @Test
    void findWithoutByUsesParameterNameAsAttribute() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/pn-find"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-pn-find"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-pn-find"));

        Files.writeString(sourceDir.resolve("Note.java"), """
                package pnfind;
                @io.github.fludakit.data.annotation.Entity
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
                package pnfind;
                import jakarta.data.repository.By;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Find;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Notes extends CrudRepository<Note, Long> {
                    @Find List<Note> byName(String title);
                    @Find List<Note> byExplicit(@By("title") String ignored);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Note.java", "Notes.java");
        String impl = sqlFlat(generatedDir.resolve("pnfind/Notes_.java"));

        assertTrue(impl.contains("FROM \"notes\" WHERE \"title\" = :title"),
                "parameter name must become the equality attribute:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("FROM \"notes\" WHERE \"title\" = :ignored"),
                "explicit @By must win over the Java parameter name:" + System.lineSeparator() + impl);
    }

    @Test
    void compoundParameterNameMapsUnderscoreToDotPath() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/pn-compound"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-pn-compound"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-pn-compound"));

        Files.writeString(sourceDir.resolve("Address.java"), """
                package pncompound;
                @io.github.fludakit.data.annotation.ValueObject
                public class Address {
                    public String city;
                    public String getCity() { return city; }
                    public void setCity(String city) { this.city = city; }
                }
                """);
        Files.writeString(sourceDir.resolve("Person.java"), """
                package pncompound;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class Person {
                    @Identity public Long id;
                    public Address address;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Address getAddress() { return address; }
                    public void setAddress(Address address) { this.address = address; }
                }
                """);
        Files.writeString(sourceDir.resolve("People.java"), """
                package pncompound;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Find;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface People extends CrudRepository<Person, Long> {
                    @Find List<Person> findByCity(String address_city);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Address.java", "Person.java", "People.java");
        String impl = sqlFlat(generatedDir.resolve("pncompound/People_.java"));

        assertTrue(impl.contains("\"address_city\"") || impl.contains("address_city"),
                "address_city parameter must filter on the flattened embedded city column:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains(":address_city"),
                "SQL bind name must stay the Java parameter name:" + System.lineSeparator() + impl);
    }

    @Test
    void queryBindsNamedParameterFromParameterNameWithoutParamAnnotation() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/pn-query"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-pn-query"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-pn-query"));

        Files.writeString(sourceDir.resolve("Book.java"), """
                package pnquery;
                @io.github.fludakit.data.annotation.Entity
                public class Book {
                    public Long id;
                    public String title;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                }
                """);
        Files.writeString(sourceDir.resolve("Books.java"), """
                package pnquery;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Query;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Books extends CrudRepository<Book, Long> {
                    @Query("SELECT id, title FROM books WHERE title = :title")
                    List<Book> search(String title);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Book.java", "Books.java");
        String impl = sqlFlat(generatedDir.resolve("pnquery/Books_.java"));

        assertTrue(impl.contains(".param(\"title\", title)"),
                "@Query must bind :title from the parameter name without @Param:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void specialParametersAreNotTreatedAsEntityAttributes() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/pn-special"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-pn-special"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-pn-special"));

        Files.writeString(sourceDir.resolve("Note.java"), """
                package pnspecial;
                @io.github.fludakit.data.annotation.Entity
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
                package pnspecial;
                import jakarta.data.Limit;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Find;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Notes extends CrudRepository<Note, Long> {
                    @Find List<Note> limited(String title, Limit limit);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Note.java", "Notes.java");
        String impl = sqlFlat(generatedDir.resolve("pnspecial/Notes_.java"));

        assertTrue(impl.contains("LIMITED_SQL = \"SELECT \"id\", \"title\" FROM \"notes\" WHERE \"title\" = :title\""),
                "only the attribute parameter must appear in the WHERE:" + System.lineSeparator() + impl);
        String limitedBody = impl.substring(impl.indexOf("public List<Note> limited"));
        limitedBody = limitedBody.substring(0, limitedBody.indexOf("@Override", 1));
        assertTrue(limitedBody.contains(".param(\"title\", title)") && !limitedBody.contains(".param(\"limit\""),
                "Limit must not become an entity attribute bind in limited():"
                        + System.lineSeparator() + limitedBody);
    }

    @Test
    void unknownParameterNameWithoutByIsACompileError() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/pn-unknown"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-pn-unknown"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-pn-unknown"));

        Files.writeString(sourceDir.resolve("Note.java"), """
                package pnunknown;
                @io.github.fludakit.data.annotation.Entity
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
                package pnunknown;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Find;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Notes extends CrudRepository<Note, Long> {
                    @Find List<Note> search(String marker);
                }
                """);

        TaskResult result = runTask(sourceDir, classesDir, generatedDir, "Note.java", "Notes.java");
        assertFalse(result.success(), "unknown bare parameter name must fail compilation");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                        && d.getMessage(Locale.ROOT).contains("Unknown entity attribute")
                        && d.getMessage(Locale.ROOT).contains("marker")),
                "expected a located unknown-attribute diagnostic:"
                        + System.lineSeparator() + result.diagnostics());
    }
}
