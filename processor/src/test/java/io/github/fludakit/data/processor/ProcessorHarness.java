package io.github.fludakit.data.processor;

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

/**
 * The compile-and-assert plumbing every processor test shares, lifted out of the one 6700-line
 * {@code RepositoryProcessorTest} so the tests could be split by SUBJECT instead of by size.
 *
 * <p>Every method here is {@code static}: a test class holds nothing but its own {@link TempDir}
 * {@code workspace}, and each helper takes the paths it needs. That is what lets the seven test
 * classes share one copy of the javac invocation and one copy of the source-flattening rules — a
 * second copy of {@code runTask} would let the classes disagree about which options the compiler
 * runs with, and a second copy of {@link #sqlFlat} would let one class pin SQL the other cannot
 * even read.</p>
 *
 * <p>Nothing here asserts anything about the PRODUCT: the helpers only run the compiler and
 * normalise what it wrote. Every assertion lives in the test that knows what it is pinning.</p>
 */
final class ProcessorHarness {

    private ProcessorHarness() {
    }

    /**
     * A write is not always fire-and-forget. A delete-by-criteria that declares {@code long} reports
     * the rows it removed — which the emitter already does, returning the {@code .update()} count —
     * so the published definition must say {@code AFFECTED}, not {@code VOID}. Before the sealed split
     * it published SINGLE; afterwards, VOID. Both were wrong, and neither was detectable from the SQL,
     * which is identical either way.
     */

    /** The one {@code QueryDefinition} field declaration in a generated holder. */

    static String declarationOf(String queries, String field) {
        return queries.lines()
                .filter(line -> line.contains(field + " = new QueryDefinition."))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + field + " field:" + System.lineSeparator() + queries));
    }

    /**
     * One {@code QueryDefinition} field in the generated holder, asserted to bind its argument with
     * {@code expected}. Matched on the FIELD rather than on the whole file: every field in the holder
     * names the same parameter, so a file-wide {@code contains} would pass even if the patterns were
     * all swapped.
     */

    static void assertDefinitionBinds(String queries, String field, String expected) {
        String declaration = declarationOf(queries, field);
        assertTrue(declaration.contains("new ParamBinding(0, \"text\", " + expected + ")"),
                field + " must bind " + expected + ":" + System.lineSeparator() + declaration);
    }

    /**
     * Pins that the method using {@code constant} binds its parameter with exactly {@code binding}.
     * Asserting the two together is what stops a same-SQL operator from passing on the wrong binding.
     */

    static void assertPinned(String impl, String constant, String binding) {
        assertTrue(impl.contains("sql(" + constant + ").param(\"text\", " + binding + ")"),
                constant + " must bind `" + binding + "`:" + System.lineSeparator() + impl);
    }

    /**
     * The body of ONE generated method, from its signature up to (excluding) the next {@code @Override}.
     * Every batch method writes the same columns, so scoping an assertion to a single body is what makes
     * it say something: a file-wide match cannot tell {@code saveAll} from {@code insertAll}.
     */

    static String bodyOf(String impl, String signature) {
        int start = impl.indexOf(signature);
        assertTrue(start >= 0, "no generated method " + signature + ":" + System.lineSeparator() + impl);
        int end = impl.indexOf("@Override", start);
        return impl.substring(start, end < 0 ? impl.length() : end);
    }

    /**
     * Pins one operator's generated constant text AND the bindings of the statement that uses it.
     * {@code constantValue} is the rendered SQL including its closing quote. The constant is what the
     * renderer produced; the binding is what the caller passes — pinning one alone would let a correct
     * SQL string pass while a parameter is bound to the wrong value, or not bound at all.
     */

    static void assertOperatorConstant(String impl, String constant, String constantValue,
                                               String binding) {
        assertTrue(impl.contains(constant + " = \"" + constantValue),
                constant + " must render `" + constantValue + "`:" + System.lineSeparator() + impl);
        assertTrue(statementOf(impl, constant + ")").contains(binding),
                constant + " must be used with binding `" + binding + "`:" + System.lineSeparator() + impl);
    }

    /** The one generated statement naming {@code constant}, for pairing a constant with its terminal. */

    static String statementOf(String impl, String constant) {
        return impl.lines()
                .filter(line -> line.contains(constant))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no statement naming " + constant + ":"
                        + System.lineSeparator() + impl))
                .trim();
    }

    static int occurrences(String text, String value) {
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

    static void writeRelationFixture(Path sourceDir) throws Exception {
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

    /**
     * Reads a generated source for SQL-constant pins. It parses the JAVA, never the SQL: each literal is
     * collapsed to its single-line logical value — a {@code """...""" } text block joins its clause lines
     * into one quoted literal, an ordinary {@code "..."} (a runtime splice fragment, kept single-line by
     * the formatter) is taken verbatim — then string escapes are undone and whitespace runs folded, so
     * neither a JavaPoet soft wrap nor the clause-per-line layout breaks the pin. The formatting itself
     * is owned by {@code SqlFormatter}; this only reads it back.
     */
    static String sqlFlat(Path generatedFile) throws Exception {
        return collapseLiterals(Files.readString(generatedFile))
                .replace("\\\"", "\"")
                .replaceAll("\\s+", " ");
    }

    /** Replace every text block with a single-line quoted literal; leave ordinary literals untouched. */
    private static String collapseLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                if (end < 0) {
                    out.append(source.substring(i));
                    break;
                }
                out.append('"').append(source.substring(i + 3, end).trim()).append('"');
                i = end + 3;
            } else if (source.charAt(i) == '"') {
                int end = i + 1;
                while (end < n) {
                    char c = source.charAt(end);
                    if (c == '\\') {
                        end += 2;
                    } else if (c == '"') {
                        break;
                    } else {
                        end++;
                    }
                }
                out.append(source, i, Math.min(end + 1, n));
                i = Math.min(end + 1, n);
            } else {
                out.append(source.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Writes the EntityCollection fixture (package {@code exa}): {@code Order} is an
     * {@code @AggregateRoot} carrying {@code List<Line> lines}; {@code Line} is a MANAGED
     * {@code @Entity} (its own {@code LineRepository}, which is what makes it a managed definition)
     * with {@code String product} and
     * a back-reference {@code Order order}, so the one-to-many inverse resolves to the fk column
     * {@code "order_id"} on the definition table {@code "line"}.
     */

    static void writeLinesFixture(Path sourceDir) throws Exception {
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

    /** One {@code CrudRepository} over a two-column entity, compiled with {@code dialect} (null = default). */

    static String saveUpsertImpl(Path workspace, String id, String dialect) throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/" + id));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-" + id));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-" + id));
        Files.writeString(sourceDir.resolve("Item.java"), """
                package ups;
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
                package ups;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Items extends CrudRepository<Item, Long> { }
                """);
        if (dialect == null) {
            compile(sourceDir, classesDir, generatedDir, "Item.java", "Items.java");
        } else {
            compileWithDialect(sourceDir, classesDir, generatedDir, dialect, "Item.java", "Items.java");
        }
        return sqlFlat(generatedDir.resolve("ups/Items_.java"));
    }

    static void compile(Path sourceDir, Path classesDir, Path generatedDir, String... fileNames) throws Exception {
        compileWithDiagnostics(sourceDir, classesDir, generatedDir, fileNames);
    }

    /** The result of running the javac task: whole-round success plus every collected diagnostic. */
    static record TaskResult(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) { }

    static TaskResult runTask(Path sourceDir, Path classesDir, Path generatedDir, String... fileNames) throws Exception {
        return runTask(sourceDir, classesDir, generatedDir, List.of(), fileNames);
    }

    static TaskResult runTask(Path sourceDir, Path classesDir, Path generatedDir,
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

    static void compileWithDialect(Path sourceDir, Path classesDir, Path generatedDir,
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
    static List<Diagnostic<? extends JavaFileObject>> compileWithDiagnostics(
            Path sourceDir, Path classesDir, Path generatedDir, String... fileNames) throws Exception {
        TaskResult result = runTask(sourceDir, classesDir, generatedDir, fileNames);
        String messages = result.diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(Object::toString).collect(Collectors.joining(System.lineSeparator()));
        assertTrue(result.success(), "Annotation processing failed:" + System.lineSeparator() + messages);
        return result.diagnostics();
    }

    static String notesOf(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.NOTE)
                .map(d -> d.getMessage(Locale.getDefault()))
                .collect(Collectors.joining(System.lineSeparator()));
    }
}
