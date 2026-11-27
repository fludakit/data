package io.github.fludakit.data.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static io.github.fludakit.data.processor.ProcessorHarness.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins three review fixes:
 * <ol>
 *   <li>generated row mappers probe optional JOIN / FK columns through one column-label set and
 *       never swallow a driver {@code SQLException};</li>
 *   <li>row mappers and metamodels are written once per compilation, even when the processor runs
 *       for more than one round;</li>
 *   <li>an unresolvable repository method is reported on THAT method and skipped, never aborting
 *       the whole repository (AGENTS.md "Fail early, per method").</li>
 * </ol>
 */
class RoundSafetyAndFailEarlyTest {

    @TempDir
    Path workspace;

    // ── 1. Row mapper error handling ─────────────────────────────────────

    @Test
    void rowMapperProbesColumnsWithoutSwallowingSqlException() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rm"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rm"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rm"));
        writeRelationFixture(sourceDir);
        Files.writeString(sourceDir.resolve("Orders.java"), """
                package relpath;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Orders extends CrudRepository<Order, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java", "Orders.java");

        String mapper = Files.readString(generatedDir.resolve("relpath/CustomerRowMapper.java"));
        assertFalse(mapper.contains("catch (SQLException"),
                "a generated row mapper must not swallow SQLException:" + System.lineSeparator() + mapper);
        assertEquals(1, occurrences(mapper, "getMetaData()"),
                "column labels must be read once per row:" + System.lineSeparator() + mapper);
        assertTrue(mapper.contains("columnLabels.contains(\"country_id\")"),
                "the FK probe must use the column-label set:" + System.lineSeparator() + mapper);
    }

    // ── 2. Multi-round safety ────────────────────────────────────────────

    /**
     * A companion processor that generates one unrelated source in round 1, forcing javac into a
     * second round in which {@code RepositoryProcessor} runs again over the same {@code TypeGraph}.
     */
    @SupportedAnnotationTypes("*")
    public static final class ExtraRoundProcessor extends AbstractProcessor {
        private boolean generated;

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (generated || roundEnv.processingOver()) return false;
            generated = true;
            try (Writer w = processingEnv.getFiler().createSourceFile("extra.Marker").openWriter()) {
                w.write("package extra; public final class Marker { }");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return false;
        }
    }

    @Test
    void generatedArtifactsAreWrittenOnceAcrossRounds() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rounds"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rounds"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rounds"));
        Files.writeString(sourceDir.resolve("Item.java"), """
                package rounds;
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
                package rounds;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Items extends CrudRepository<Item, Long> { }
                """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessors(
                sourceDir, classesDir, generatedDir,
                RepositoryProcessor.class.getName() + "," + ExtraRoundProcessor.class.getName(),
                "Item.java", "Items.java");

        String errors = errorsOf(diagnostics);
        assertTrue(errors.isEmpty(), "a second round must not recreate generated files:"
                + System.lineSeparator() + errors);
        assertTrue(Files.exists(generatedDir.resolve("rounds/ItemRowMapper.java")));
        assertTrue(Files.exists(generatedDir.resolve("extra/Marker.java")),
                "the companion processor must have forced a second round");
    }

    // ── 3. Fail-early method resolution ──────────────────────────────────

    @Test
    void unbindableMethodIsReportedOnTheMethodAndOnlyThatMethodIsSkipped() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/failearly"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-failearly"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-failearly"));
        writePersonFixture(sourceDir);
        String repository = """
                package failearly;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface People extends CrudRepository<Person, Long> {
                    List<Person> findByName(String name);
                    List<Person> findByNameAndEmail(String name);
                }
                """;
        Files.writeString(sourceDir.resolve("People.java"), repository);

        TaskResult result = runTask(sourceDir, classesDir, generatedDir, "Person.java", "People.java");

        assertFalse(result.success(), "an unbindable method must fail the build");
        Diagnostic<? extends JavaFileObject> error = firstErrorMentioning(result, "findByNameAndEmail");
        assertEquals(lineOf(repository, "findByNameAndEmail"), error.getLineNumber(),
                "the error must point at the offending method, not the repository:" + System.lineSeparator() + error);
        assertTrue(error.getMessage(Locale.ROOT).contains("query parameter"),
                "the error must carry the binding reason:" + System.lineSeparator() + error);
        assertTrue(result.diagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                        && d.getMessage(Locale.ROOT).contains("could not generate People_")),
                "the repository as a whole must not be aborted:" + System.lineSeparator() + errorsOf(result.diagnostics()));

        String impl = Files.readString(generatedDir.resolve("failearly/People_.java"));
        assertTrue(impl.contains("findByName(String name)"), "the valid sibling method must still be generated");
    }

    @Test
    void unparseableQueryByMethodNameReportsTheParserReason() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/qbnreason"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-qbnreason"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-qbnreason"));
        writePersonFixture(sourceDir);
        Files.writeString(sourceDir.resolve("People.java"), """
                package failearly;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface People extends CrudRepository<Person, Long> {
                    void deleteDistinctByName(String name);
                }
                """);

        TaskResult result = runTask(sourceDir, classesDir, generatedDir, "Person.java", "People.java");

        assertFalse(result.success());
        Diagnostic<? extends JavaFileObject> error = firstErrorMentioning(result, "deleteDistinctByName");
        assertTrue(error.getMessage(Locale.ROOT).contains("Distinct is only meaningful"),
                "expected the parser's reason, not the generic message:" + System.lineSeparator() + error);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static void writePersonFixture(Path sourceDir) throws Exception {
        Files.writeString(sourceDir.resolve("Person.java"), """
                package failearly;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class Person {
                    @Identity Long id;
                    String name;
                    String email;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public String getEmail() { return email; }
                    public void setEmail(String email) { this.email = email; }
                }
                """);
    }

    private static Diagnostic<? extends JavaFileObject> firstErrorMentioning(TaskResult result, String text) {
        return result.diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR && d.getMessage(Locale.ROOT).contains(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no error mentioning " + text + ":"
                        + System.lineSeparator() + errorsOf(result.diagnostics())));
    }

    private static long lineOf(String source, String text) {
        List<String> lines = source.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) return i + 1;
        }
        throw new AssertionError(text + " not in source");
    }

    private static String errorsOf(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(Object::toString)
                .collect(Collectors.joining(System.lineSeparator()));
    }

    /** Like {@code ProcessorHarness.runTask}, but with an explicit {@code -processor} list. */
    private static List<Diagnostic<? extends JavaFileObject>> compileWithProcessors(
            Path sourceDir, Path classesDir, Path generatedDir, String processors, String... fileNames)
            throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A system Java compiler is required to run this test");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            java.io.File[] files = new java.io.File[fileNames.length];
            for (int i = 0; i < fileNames.length; i++) files[i] = sourceDir.resolve(fileNames[i]).toFile();
            List<String> options = List.of(
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", classesDir.toString(),
                    "-s", generatedDir.toString(),
                    "-parameters",
                    "-processor", processors);
            compiler.getTask(null, fm, diagnostics, options, null, fm.getJavaFileObjects(files)).call();
            return diagnostics.getDiagnostics();
        }
    }
}
