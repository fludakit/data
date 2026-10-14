package io.github.fludakit.data.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.github.fludakit.data.processor.ProcessorHarness.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the QUERY a method name (or an annotated criteria) compiles into: which operator renders which
 * SQL, which traversal renders a LEFT JOIN and which renders an EXISTS semi-join, and what the
 * published query definition says.
 *
 * <p>Split out of {@code RepositoryProcessorTest}; the shared compile-and-assert plumbing
 * lives in {@link ProcessorHarness}.</p>
 */
class QueryByMethodNameEmissionTest {

    @TempDir
    Path workspace;

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

        // The published definition must carry the same rule: the SQL text is identical across the
        // LIKE family, so without the pattern on the binding a reader of the holder cannot tell
        // CONTAINS from STARTS_WITH from ENDS_WITH — the exact ambiguity the binding above resolves.
        String queries = sqlFlat(generatedDir.resolve("strop/NotesQueries.java"));
        assertDefinitionBinds(queries, "FIND_BY_TEXT_CONTAINS_QUERY", "LikePattern.BOTH");
        assertDefinitionBinds(queries, "FIND_BY_TEXT_STARTS_WITH_QUERY", "LikePattern.TRAILING");
        assertDefinitionBinds(queries, "FIND_BY_TEXT_ENDS_WITH_QUERY", "LikePattern.LEADING");
        assertDefinitionBinds(queries, "FIND_BY_TEXT_NOT_CONTAINS_QUERY", "LikePattern.BOTH");
        assertDefinitionBinds(queries, "FIND_BY_TEXT_NOT_STARTS_WITH_QUERY", "LikePattern.TRAILING");
        assertDefinitionBinds(queries, "FIND_BY_TEXT_NOT_ENDS_WITH_QUERY", "LikePattern.LEADING");
        // Like takes a pattern, not a substring: the caller's own wildcards are bound untouched.
        assertDefinitionBinds(queries, "FIND_BY_TEXT_LIKE_QUERY", "LikePattern.NONE");
    }

    @Test
    void pinsComparisonRangeAndMembershipOperatorSql() throws Exception {
        // Unlike the LIKE family these operators render DISTINCT predicate tokens (<, <=, >, >=,
        // BETWEEN, NOT BETWEEN, IN, NOT IN), so the SQL text alone can tell them apart — but only if
        // the pin names the constant. A file-wide contains("<") passes on any of the four, and a
        // swapped arm still compiles: each is a separate arm in the parser AND in the renderer, so
        // findByQuantityLessThan emitting ">=" would be invisible until a row came back wrong.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/cmpop"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-cmpop"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-cmpop"));

        Files.writeString(sourceDir.resolve("Ticket.java"), """
                package cmpop;
                public class Ticket {
                    public Long id;
                    public int quantity;
                    public String status;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public int getQuantity() { return quantity; }
                    public void setQuantity(int quantity) { this.quantity = quantity; }
                    public String getStatus() { return status; }
                    public void setStatus(String status) { this.status = status; }
                }
                """);
        Files.writeString(sourceDir.resolve("Tickets.java"), """
                package cmpop;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Tickets extends CrudRepository<Ticket, Long> {
                    List<Ticket> findByQuantityLessThan(int quantity);
                    List<Ticket> findByQuantityLessThanEqual(int quantity);
                    List<Ticket> findByQuantityGreaterThan(int quantity);
                    List<Ticket> findByQuantityGreaterThanEqual(int quantity);
                    List<Ticket> findByQuantityBetween(int low, int high);
                    List<Ticket> findByQuantityNotBetween(int low, int high);
                    List<Ticket> findByStatusIn(List<String> statuses);
                    List<Ticket> findByStatusNotIn(List<String> statuses);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Ticket.java", "Tickets.java");

        String impl = sqlFlat(generatedDir.resolve("cmpop/TicketsImpl.java"));
        String table = "SELECT \"id\", \"quantity\", \"status\" FROM \"ticket\" WHERE ";
        assertOperatorConstant(impl, "SQL_FIND_BY_QUANTITY_LESS_THAN",
                table + "\"quantity\" < :quantity\"", "param(\"quantity\", quantity)");
        assertOperatorConstant(impl, "SQL_FIND_BY_QUANTITY_LESS_THAN_EQUAL",
                table + "\"quantity\" <= :quantity\"", "param(\"quantity\", quantity)");
        assertOperatorConstant(impl, "SQL_FIND_BY_QUANTITY_GREATER_THAN",
                table + "\"quantity\" > :quantity\"", "param(\"quantity\", quantity)");
        assertOperatorConstant(impl, "SQL_FIND_BY_QUANTITY_GREATER_THAN_EQUAL",
                table + "\"quantity\" >= :quantity\"", "param(\"quantity\", quantity)");
        // BETWEEN is the only one taking TWO arguments, so it needs two placeholder names. Both are
        // derived from the FIRST parameter, and they are named for the ROLE of each bound
        // (low_from / low_to) rather than numbered — ":low_1" forced a reader to re-derive which
        // bound it was every time.
        assertOperatorConstant(impl, "SQL_FIND_BY_QUANTITY_BETWEEN",
                table + "\"quantity\" BETWEEN :low_from AND :low_to\"",
                "param(\"low_from\", low).param(\"low_to\", high)");
        assertOperatorConstant(impl, "SQL_FIND_BY_QUANTITY_NOT_BETWEEN",
                table + "\"quantity\" NOT BETWEEN :low_from AND :low_to\"",
                "param(\"low_from\", low).param(\"low_to\", high)");
        // IN binds one parameter that the JDBC client expands: the placeholder is NOT repeated.
        assertOperatorConstant(impl, "SQL_FIND_BY_STATUS_IN",
                table + "\"status\" IN (:statuses)\"", "param(\"statuses\", statuses)");
        assertOperatorConstant(impl, "SQL_FIND_BY_STATUS_NOT_IN",
                table + "\"status\" NOT IN (:statuses)\"", "param(\"statuses\", statuses)");
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
        // Distinct is a deliberate superset of the Jakarta Data BNF, so it is a KEYWORD here, not
        // ignored text: the name states the de-duplication and the statement must honour it.
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
        // The assembly point: the parsed name supplies the operation and the Distinct flag, the method
        // signature supplies the result kind and the bindings — one QueryDefinition carries both halves.
        assertTrue(queries.contains("FIND_DISTINCT_BY_TEXT_QUERY = new QueryDefinition.Read(ResultKind.LIST,"
                        + " List.of(Predicate.of(\"text\", Operator.EQ, \"text\","
                        + " Connector.AND, false)), List.of(), PagingMode.NONE, new LimitSpec(0, 0),"
                        + " List.of(), List.of(new ParamBinding(0, \"text\", LikePattern.NONE)), true);"),
                "the published read must report distinct:" + System.lineSeparator() + queries);
        // The count of the same name is its own variant: no result kind, no sorts, no row window —
        // just the criteria and the Distinct flag.
        assertTrue(queries.contains("COUNT_DISTINCT_BY_TEXT_QUERY = new QueryDefinition.Count("
                        + "List.of(Predicate.of(\"text\", Operator.EQ, \"text\", Connector.AND, false)),"
                        + " List.of(new ParamBinding(0, \"text\", LikePattern.NONE)), true);"),
                "the published count must report distinct:" + System.lineSeparator() + queries);
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
                        && d.getMessage(Locale.ROOT).contains("Not a query method name: findByTextAnd")),
                "expected the unparseable-name diagnostic to carry the name the grammar could not read:"
                        + System.lineSeparator() + result.diagnostics());
    }

    @Test
    void aRejectedQueryByNameReportsTheReasonTheParserComputed() throws Exception {
        // The parser knows WHY a name is wrong; the diagnostic used to throw that away and restate
        // the shape instead. `Not` on an operator the spec does not negate is the clearest case:
        // the name parses, so "not a valid query-by-method name" says nothing the author can act on.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/qbn-reason"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-qbn-reason"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-qbn-reason"));

        Files.writeString(sourceDir.resolve("Memo.java"), """
                package qbnreason;
                public class Memo {
                    public Long id;
                    public Long price;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Long getPrice() { return price; }
                    public void setPrice(Long price) { this.price = price; }
                }
                """);
        Files.writeString(sourceDir.resolve("Memos.java"), """
                package qbnreason;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Memos extends CrudRepository<Memo, Long> {
                    List<Memo> findByPriceNotLessThan(Long price);
                }
                """);

        TaskResult result = runTask(sourceDir, classesDir, generatedDir, "Memo.java", "Memos.java");

        assertFalse(result.success(), "an unsupported Not composition must not silently render <>");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                        && d.getMessage(Locale.ROOT).contains("Not cannot be combined with LESS_THAN")),
                "the diagnostic must carry the reason the parser worked out, not a restatement of the"
                        + " shape:" + System.lineSeparator() + result.diagnostics());
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
    void methodNamePredicateThroughElementCollectionEmitsExists() throws Exception {
        // findByOrderItemsQuantityGreaterThan — the qbn property is the concatenated camel
        // "orderItemsQuantity"; resolveCompilePath splits it at hump boundaries into the element-list
        // head "orderItems" + leaf "quantity", and the emitted SQL constant carries the EXISTS
        // semi-join the traversal needs (a to-many head must never fan out into a LEFT JOIN). Param
        // binding still uses the JAVA PARAMETER NAME.
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
        // = "order_order_items" (a bare "order_items" would drop the parent prefix). The to-many head
        // renders an EXISTS semi-join, not a LEFT JOIN. sqlFlat folds whitespace so a
        // JavaPoet soft wrap cannot break the byte pin.
        String impl = sqlFlat(generatedDir.resolve("relpath/OrdersImpl.java"));
        assertTrue(impl.contains("FROM \"order\" WHERE EXISTS (SELECT 1 FROM \"order_order_items\" "
                        + "\"order_order_items\" WHERE \"order_order_items\".\"order_id\" = \"order\".\"id\" "
                        + "AND \"order_order_items\".\"quantity\" > :quantity)"),
                "element-collection predicate must render EXISTS with the child table as its own alias:"
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
        assertTrue(impl.contains("SELECT COUNT(*) FROM \"order\" WHERE EXISTS (SELECT 1 FROM \"order_order_items\" \"order_order_items\" WHERE \"order_order_items\".\"order_id\" = \"order\".\"id\" AND \"order_order_items\".\"quantity\" > :quantity)"),
                "countBy through a to-many must render EXISTS on the unjoined COUNT(*) total:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("LEFT JOIN"),
                "a to-many countBy must NOT splice a LEFT JOIN anywhere:" + System.lineSeparator() + impl);
    }

    @Test
    void deleteByThroughRelationRendersExistsSemiJoin() throws Exception {
        // A deleteBy whose predicate traverses a relation renders a correlated EXISTS semi-join
        // through SqlBuilder.build's delete mode — a joined DELETE ... LEFT JOIN is invalid SQL on
        // H2/PostgreSQL/SQL Server — and emits NO delete NOTE. The old degrade to the join-free legacy
        // predicate could match the wrong rows and is removed. Guards: a LOCAL-property
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
        // compileWithDiagnostics throws on a failed round, so reaching here proves the EXISTS
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
        // A to-many delete renders the SAME EXISTS conjunct find/count use (delete now
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

        assertTrue(impl.contains("DELETE FROM \"order\" WHERE EXISTS (SELECT 1 FROM \"order_order_items\" \"order_order_items\" WHERE \"order_order_items\".\"order_id\" = \"order\".\"id\" AND \"order_order_items\".\"quantity\" > :quantity)"),
                "deleteBy through a to-many must render the to-many EXISTS conjunct:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("DELETE FROM \"order\" WHERE \"status\" = :status AND EXISTS (SELECT 1 FROM \"customer\" \"customer\" WHERE \"customer\".\"id\" = \"order\".\"customer_id\" AND \"customer\".\"name\" = :name)"),
                "mixed delete: bare local conjunct first, reversed-edge EXISTS conjunct second:" + System.lineSeparator() + impl);
        String notes = notesOf(diagnostics);
        assertTrue(notes.contains("path 'customerCountryCode' traverses more than one relation; not supported"),
                "the multi-hop delete path must keep the verbatim resolver NOTE:" + System.lineSeparator() + notes);
        assertTrue(!impl.contains("EXISTS (SELECT 1 FROM \"country\"") && !impl.contains("LEFT JOIN"),
                "a rejected multi-hop delete renders neither EXISTS nor a join:" + System.lineSeparator() + impl);
        assertTrue(!notes.contains("DELETE through a relation"),
                "the delete-degrade NOTE text is gone:" + System.lineSeparator() + notes);
    }

    @Test
    void predicateThroughEntityCollectionRendersExistsNotJoin() throws Exception {
        // findByLinesProduct - the head "lines" is a Prop.EntityCollection (to-many). The predicate
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
        assertTrue(impl.contains("EXISTS (SELECT 1 FROM \"line\" \"line\" WHERE \"line\".\"order_id\" = \"order\".\"id\" AND \"line\".\"product\" = :product)"),
                "entity-list predicate must render EXISTS, alias-qualified by the relation property:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("LEFT JOIN"),
                "a to-many predicate must NOT splice a LEFT JOIN:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"product\", product)"),
                "the bound parameter must stay the JAVA PARAMETER NAME:" + System.lineSeparator() + impl);
    }

    @Test
    void qualifiedRootSelectParityGuardPinsBothHelperCallShapes() throws Exception {
        // Parity guard. The SQL_FIND_ALL_QUALIFIED emission and the joined-find branch now
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
        assertTrue(impl.contains("SELECT \"id\", \"status\" FROM \"order\" WHERE EXISTS (SELECT 1 FROM \"line\" \"line\" WHERE \"line\".\"order_id\" = \"order\".\"id\" AND \"line\".\"product\" = :product)"),
                "the no-join helper mode must keep the bare root projection:" + System.lineSeparator() + impl);
        // Mode 2 (qualified == true, no join spliced by the constant itself): the findAll constant.
        assertTrue(impl.contains("SELECT \"order\".\"id\", \"order\".\"status\" FROM \"order\""),
                "the qualified helper mode must list every root column:" + System.lineSeparator() + impl);
    }

    @Test
    void predicateThroughElementCollectionRendersExistsNotJoin() throws Exception {
        // findByContactsValue - the head "contacts" is a Prop.ElementCollection; the EXISTS renders
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
        assertTrue(impl.contains("EXISTS (SELECT 1 FROM \"profile_contacts\" \"profile_contacts\" WHERE \"profile_contacts\".\"profile_id\" = \"profile\".\"id\" AND \"profile_contacts\".\"value\" = :contactsValue)"),
                "element-list predicate must render EXISTS over the child table:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("LEFT JOIN"),
                "a to-many predicate must NOT splice a LEFT JOIN:" + System.lineSeparator() + impl);
    }

    @Test
    void predicateThroughReferenceStillLeftJoinsWithoutExists() throws Exception {
        // Guard: a to-one (Reference) head keeps the existing LEFT JOIN splice BYTE-IDENTICAL
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
        // to-one predicate (Reference head "customer") and a to-many predicate (ElementCollection head
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
        assertTrue(impl.contains("FROM \"order\" LEFT JOIN \"customer\" AS \"customer\" ON \"order\".\"customer_id\" = \"customer\".\"id\" WHERE \"customer\".\"name\" = :name AND EXISTS (SELECT 1 FROM \"order_order_items\" \"order_order_items\" WHERE \"order_order_items\".\"order_id\" = \"order\".\"id\" AND \"order_order_items\".\"quantity\" > :quantity)"),
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
    void countThroughEntityCollectionUsesExistsAndStaysUnjoinedTotal() throws Exception {
        // countByLinesProduct keeps the COUNT(*) projection, renders the same EXISTS conjunct
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
        assertTrue(impl.contains("SELECT COUNT(*) FROM \"order\" WHERE EXISTS (SELECT 1 FROM \"line\" \"line\" WHERE \"line\".\"order_id\" = \"order\".\"id\" AND \"line\".\"product\" = :product)"),
                "countBy through an EntityCollection must render EXISTS on the unjoined COUNT(*) total:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("LEFT JOIN"),
                "no LEFT JOIN anywhere:" + System.lineSeparator() + impl);
    }
}
