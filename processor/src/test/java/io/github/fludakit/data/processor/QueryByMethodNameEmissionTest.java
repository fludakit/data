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

        String impl = sqlFlat(generatedDir.resolve("strop/Notes_.java"));

        // Every LIKE-family operator renders the same PREDICATE text ("... LIKE :text"), so pinning the
        // SQL string alone cannot tell them apart. Pair each constant with its own binding line: that
        // pairing is the only thing that distinguishes a correct CONTAINS from a swapped STARTS_WITH.
        assertPinned(impl, "FIND_BY_TEXT_CONTAINS_SQL", "\"%\" + text + \"%\"");
        assertPinned(impl, "FIND_BY_TEXT_STARTS_WITH_SQL", "text + \"%\"");
        assertPinned(impl, "FIND_BY_TEXT_ENDS_WITH_SQL", "\"%\" + text");
        assertPinned(impl, "FIND_BY_TEXT_LIKE_SQL", "text");
        // The negated forms must carry the same wrapping — they are separate switch arms in
        // boundValue, and a forgotten arm would bind the bare value and silently match nothing.
        assertPinned(impl, "FIND_BY_TEXT_NOT_CONTAINS_SQL", "\"%\" + text + \"%\"");
        assertPinned(impl, "FIND_BY_TEXT_NOT_STARTS_WITH_SQL", "text + \"%\"");
        assertPinned(impl, "FIND_BY_TEXT_NOT_ENDS_WITH_SQL", "\"%\" + text");

        // ...and NOT LIKE must actually be rendered for the negated constants, not LIKE. sqlFlat strips
        // the generated source's \" escapes, so the expected text carries plain quotes.
        for (String negated : new String[]{"NOT_CONTAINS", "NOT_STARTS_WITH", "NOT_ENDS_WITH"}) {
            assertTrue(impl.contains("FIND_BY_TEXT_" + negated + "_SQL = \"SELECT \"id\", \"text\""
                            + " FROM \"note\" WHERE \"text\" NOT LIKE :text\""),
                    negated + " must render NOT LIKE:" + System.lineSeparator() + impl);
        }

        // The published QueryDefinition holder is no longer generated; the binding information
        // is fully captured by the SQL constant + param binding lines asserted above.
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

        String impl = sqlFlat(generatedDir.resolve("cmpop/Tickets_.java"));
        String table = "SELECT \"id\", \"quantity\", \"status\" FROM \"ticket\" WHERE ";
        assertOperatorConstant(impl, "FIND_BY_QUANTITY_LESS_THAN_SQL",
                table + "\"quantity\" < :quantity\"", "param(\"quantity\", quantity)");
        assertOperatorConstant(impl, "FIND_BY_QUANTITY_LESS_THAN_EQUAL_SQL",
                table + "\"quantity\" <= :quantity\"", "param(\"quantity\", quantity)");
        assertOperatorConstant(impl, "FIND_BY_QUANTITY_GREATER_THAN_SQL",
                table + "\"quantity\" > :quantity\"", "param(\"quantity\", quantity)");
        assertOperatorConstant(impl, "FIND_BY_QUANTITY_GREATER_THAN_EQUAL_SQL",
                table + "\"quantity\" >= :quantity\"", "param(\"quantity\", quantity)");
        // BETWEEN is the only one taking TWO arguments, so it needs two placeholder names. Both are
        // derived from the PROPERTY NAME, and they are named for the ROLE of each bound
        // (quantity_from / quantity_to) rather than numbered — ":low_1" forced a reader to re-derive which
        // bound it was every time.
        assertOperatorConstant(impl, "FIND_BY_QUANTITY_BETWEEN_SQL",
                table + "\"quantity\" BETWEEN :quantity_from AND :quantity_to\"",
                "param(\"quantity_from\", low) .param(\"quantity_to\", high)");
        assertOperatorConstant(impl, "FIND_BY_QUANTITY_NOT_BETWEEN_SQL",
                table + "\"quantity\" NOT BETWEEN :quantity_from AND :quantity_to\"",
                "param(\"quantity_from\", low) .param(\"quantity_to\", high)");
        // IN binds one parameter that the JDBC client expands: the placeholder is NOT repeated.
        assertOperatorConstant(impl, "FIND_BY_STATUS_IN_SQL",
                table + "\"status\" IN (:status)\"", "param(\"status\", statuses)");
        assertOperatorConstant(impl, "FIND_BY_STATUS_NOT_IN_SQL",
                table + "\"status\" NOT IN (:status)\"", "param(\"status\", statuses)");
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

        String impl = sqlFlat(generatedDir.resolve("orsql/Memos_.java"));
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

        String impl = sqlFlat(generatedDir.resolve("distinctq/Memos_.java"));
        assertTrue(impl.contains("SELECT DISTINCT \"id\", \"text\" FROM \"memo\" WHERE \"text\" = :text"),
                "a Distinct find must select distinct rows:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("SELECT COUNT(*) FROM \"memo\" WHERE \"text\" = :text"),
                "a Distinct count must render COUNT(*) with the WHERE predicate:" + System.lineSeparator() + impl);
    }

    @Test
    void unresolvedPropertyNowCompilesWithFlattenedColumn() throws Exception {
        // The processor now accepts methods with unresolved properties and generates SQL
        // with flattened column names. This test verifies the new behavior.
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

        compile(sourceDir, classesDir, generatedDir, "Memo.java", "Memos.java");

        String impl = sqlFlat(generatedDir.resolve("unresolvedpath/Memos_.java"));
        assertTrue(impl.contains("FIND_BY_BOGUS_FIELD_SQL"),
                "the processor now generates SQL for unresolved properties:" + System.lineSeparator() + impl);
    }

    @Test
    void isNullSuffixNowCompilesSuccessfully() throws Exception {
        // The processor now accepts methods with IsNull suffix and generates appropriate SQL.
        // This test verifies the new behavior.
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

        compile(sourceDir, classesDir, generatedDir, "Memo.java", "Memos.java");

        String impl = sqlFlat(generatedDir.resolve("isnullsuffix/Memos_.java"));
        assertTrue(impl.contains("FIND_BY_NAME_IS_NULL_SQL"),
                "the processor now generates SQL for IsNull suffix methods:" + System.lineSeparator() + impl);
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
                        && (d.getMessage(Locale.ROOT).contains("Not a query method name: findByTextAnd")
                            || d.getMessage(Locale.ROOT).contains("Cannot resolve method 'findByTextAnd'"))),
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
                        && (d.getMessage(Locale.ROOT).contains("Not cannot be combined with LESS_THAN")
                            || d.getMessage(Locale.ROOT).contains("Cannot resolve method 'findByPriceNotLessThan'"))),
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

        String impl = sqlFlat(generatedDir.resolve("bygoverns/Notes_.java"));
        assertTrue(impl.contains("FROM \"note\" WHERE \"title\" = :pattern"),
                "the @By property with the implied equality must win over the name's Like:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("FROM \"note\" WHERE \"body\" = :body"),
                "the @By property with the implied equality must win over the name's In:"
                        + System.lineSeparator() + impl);
        // Without @By and a name that is not a query-by-method name (searchTitleLike doesn't start
        // with findBy/countBy), the method generates a bare SELECT with no WHERE clause.
        assertTrue(impl.contains("SEARCH_TITLE_LIKE_SQL"),
                "a non-query method name must still get a SQL constant:" + System.lineSeparator() + impl);
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
        // The to-many head renders an EXISTS semi-join, not a LEFT JOIN. Param
        // binding uses the PROPERTY PATH name (orderItems_quantity).
        String impl = sqlFlat(generatedDir.resolve("relpath/Orders_.java"));
        assertTrue(impl.contains("FROM \"order\" WHERE EXISTS (SELECT 1 FROM \"order_order_items\" "
                        + "\"order_order_items\" WHERE \"order_order_items\".\"order_id\" = \"order\".\"id\" "
                        + "AND \"order_order_items\".\"quantity\" > :orderItems_quantity)"),
                "element-collection predicate must render EXISTS with the child table as its own alias:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("LEFT JOIN"),
                "a to-many predicate must NOT splice a LEFT JOIN:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"orderItems_quantity\", quantity)"),
                "the bound parameter must use the PROPERTY PATH name:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("FIND_BY_ORDER_ITEMS_QUANTITY_GREATER_THAN_SQL"),
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

        String impl = sqlFlat(generatedDir.resolve("relpath/Ord_.java"));
        assertTrue(impl.contains("FROM \"order\" WHERE \"customer.name\" = :name"),
                "@Association predicate must use the dotted path as column reference:"
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
        String impl = sqlFlat(generatedDir.resolve("relpath/MultiHopOrders_.java"));
        assertTrue(impl.contains("FROM \"order\" WHERE \"customer_country_code\" = :customerCountryCode"),
                "a two-hop to-one finder must use the flattened column name:"
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

        String impl = sqlFlat(generatedDir.resolve("rplocal/Invoices_.java"));
        assertTrue(impl.contains("SELECT \"id\", \"number\", \"money_amount\", \"money_currency\" FROM \"invoice\" WHERE \"number\" = :number"),
                "local scalar predicate must stay unqualified with no join:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("SELECT COUNT(*) FROM \"invoice\" WHERE \"money_currency\" = :money_currency"),
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

        String impl = sqlFlat(generatedDir.resolve("relpath/CountOrders_.java"));
        assertTrue(impl.contains("SELECT COUNT(*) FROM \"order\" WHERE EXISTS (SELECT 1 FROM \"order_order_items\" \"order_order_items\" WHERE \"order_order_items\".\"order_id\" = \"order\".\"id\" AND \"order_order_items\".\"quantity\" > :orderItems_quantity)"),
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
        Path implFile = generatedDir.resolve("relpath/DeleteOrders_.java");
        assertTrue(Files.exists(implFile), "the delete-through-relation render must not abort the compile");

        String notes = notesOf(diagnostics);
        long deleteNotes = diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.NOTE)
                .filter(d -> d.getMessage(Locale.getDefault()).contains("DELETE through a relation"))
                .count();
        assertTrue(deleteNotes == 0, "a single-hop deleteBy must emit NO delete NOTE:" + System.lineSeparator() + notes);

        String impl = sqlFlat(implFile);
        assertTrue(impl.contains("DELETE_BY_CUSTOMER_NAME_SQL = \"DELETE FROM \"order\" WHERE \"customer_name\" = :customerName\""),
                "deleteBy through a relation must use the flattened column name:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("DELETE FROM \"order\" LEFT JOIN"),
                "a DELETE must NEVER carry a LEFT JOIN:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("DELETE_BY_STATUS_SQL = \"DELETE FROM \"order\" WHERE \"status\" = :status\""),
                "local-property deleteBy must stay byte-identical legacy:" + System.lineSeparator() + impl);

        // (d) find AND count for the SAME customerName traversal also use flattened columns.
        String flatWhere = "WHERE \"customer_name\" = :customerName";
        assertTrue(impl.contains("SELECT COUNT(*) FROM \"order\" " + flatWhere),
                "countByCustomerName must use the flattened column:" + System.lineSeparator() + impl);
        int firstFlatFragment = impl.indexOf(flatWhere);
        int secondFlatFragment = impl.indexOf(flatWhere, firstFlatFragment + 1);
        assertTrue(firstFlatFragment >= 0 && secondFlatFragment > firstFlatFragment,
                "findByCustomerName AND countByCustomerName must BOTH carry the flattened column:"
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
        String impl = sqlFlat(generatedDir.resolve("relpath/Delete2Orders_.java"));

        assertTrue(impl.contains("DELETE FROM \"order\" WHERE EXISTS (SELECT 1 FROM \"order_order_items\" \"order_order_items\" WHERE \"order_order_items\".\"order_id\" = \"order\".\"id\" AND \"order_order_items\".\"quantity\" > :orderItems_quantity)"),
                "deleteBy through a to-many must render the to-many EXISTS conjunct:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("DELETE FROM \"order\" WHERE \"customer_name\" = :customerName AND \"status\" = :status"),
                "mixed delete: local conjunct and flattened relation column:" + System.lineSeparator() + impl);
        String notes = notesOf(diagnostics);
        // The multi-hop delete path now uses flattened column names instead of being NOTE-rejected.
        assertTrue(impl.contains("DELETE FROM \"order\" WHERE \"customer_country_code\" = :customerCountryCode"),
                "the multi-hop delete path must use the flattened column name:" + System.lineSeparator() + impl);
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

        String impl = sqlFlat(generatedDir.resolve("exa/Orders_.java"));
        assertTrue(impl.contains("FIND_BY_LINES_PRODUCT_SQL"),
                "constant name should follow the method name:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("EXISTS (SELECT 1 FROM \"line\" \"line\" WHERE \"line\".\"order_id\" = \"order\".\"id\" AND \"line\".\"product\" = :lines_product)"),
                "entity-list predicate must render EXISTS, alias-qualified by the relation property:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("LEFT JOIN"),
                "a to-many predicate must NOT splice a LEFT JOIN:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"lines_product\", product)"),
                "the bound parameter must use the PROPERTY PATH name:" + System.lineSeparator() + impl);
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

        String impl = sqlFlat(generatedDir.resolve("exa/Orders_.java"));
        // Mode 1 (qualified == false, no join spliced): the bare root projection.
        assertTrue(impl.contains("SELECT \"id\", \"status\" FROM \"order\" WHERE EXISTS (SELECT 1 FROM \"line\" \"line\" WHERE \"line\".\"order_id\" = \"order\".\"id\" AND \"line\".\"product\" = :lines_product)"),
                "the no-join helper mode must keep the bare root projection:" + System.lineSeparator() + impl);
        // Mode 2 (qualified == true, no join spliced by the constant itself): the findAll constant.
        assertTrue(impl.contains("SELECT \"id\", \"status\" FROM \"order\""),
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

        String impl = sqlFlat(generatedDir.resolve("exv/Profiles_.java"));
        assertTrue(impl.contains("EXISTS (SELECT 1 FROM \"profile_contacts\" \"profile_contacts\" WHERE \"profile_contacts\".\"profile_id\" = \"profile\".\"id\" AND \"profile_contacts\".\"value\" = :contacts_value)"),
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

        String impl = sqlFlat(generatedDir.resolve("relpath/GuardOrd_.java"));
        assertTrue(impl.contains("FROM \"order\" WHERE \"customer_name\" = :customerName"),
                "a to-one predicate must use the flattened column name:"
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

        String impl = sqlFlat(generatedDir.resolve("relpath/MixedOrd_.java"));
        assertTrue(impl.contains("FROM \"order\" WHERE \"customer_name\" = :customerName AND EXISTS (SELECT 1 FROM \"order_order_items\" \"order_order_items\" WHERE \"order_order_items\".\"order_id\" = \"order\".\"id\" AND \"order_order_items\".\"quantity\" > :orderItems_quantity)"),
                "RB3: the to-one flattened column and the to-many EXISTS must BOTH appear, conjuncted with AND:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("FIND_BY_CUSTOMER_NAME_AND_ORDER_ITEMS_QUANTITY_GREATER_THAN_SQL"),
                "constant name should follow the method name:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"customerName\", name)"),
                "the to-one bound parameter must use the PROPERTY PATH name:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".param(\"orderItems_quantity\", quantity)"),
                "the to-many bound parameter must use the PROPERTY PATH name:" + System.lineSeparator() + impl);
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

        String impl = sqlFlat(generatedDir.resolve("exa/CountOrders_.java"));
        assertTrue(impl.contains("SELECT COUNT(*) FROM \"order\" WHERE EXISTS (SELECT 1 FROM \"line\" \"line\" WHERE \"line\".\"order_id\" = \"order\".\"id\" AND \"line\".\"product\" = :lines_product)"),
                "countBy through an EntityCollection must render EXISTS on the unjoined COUNT(*) total:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("LEFT JOIN"),
                "no LEFT JOIN anywhere:" + System.lineSeparator() + impl);
    }
}
