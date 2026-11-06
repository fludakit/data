package io.github.fludakit.data.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for the metamodel centerpiece: how {@link TypeDefinition} answers the three
 * rendering questions — the table's physical columns, the path-keyed column index, and the
 * resolution of a logical path to its placement (leaf + edges) — plus its classification helpers.
 *
 * <p>The model mirrors DRAFT.MD's examples: an Author entity, a Book aggregate holding a to-one
 * author and an entity collection of lines, and a Customer aggregate with an embedded Address and
 * an element collection of tags.</p>
 */
class TypeDefinitionTest {

    private static final TypeDefinition.ValueObject ADDRESS = new TypeDefinition.ValueObject(
            "demo.Address",
            List.of(
                    new TypeDefinition.Prop.Scalar("street", "String", "street", true),
                    new TypeDefinition.Prop.Scalar("zipcode", "String", "zipcode", true)),
            List.of(), List.of(), List.of(), List.of());

    private static final TypeDefinition.ValueObject TAG = new TypeDefinition.ValueObject(
            "demo.Tag",
            List.of(new TypeDefinition.Prop.Scalar("label", "String", "label", true)),
            List.of(), List.of(), List.of(), List.of());

    private static final TypeDefinition.Entity AUTHOR = new TypeDefinition.Entity(
            "demo.Author", "author",
            new TypeDefinition.Prop.Id("id", "Long", "id", TypeDefinition.Prop.Id.GeneratedType.IDENTITY),
            null,
            List.of(new TypeDefinition.Prop.Scalar("name", "String", "name", true)),
            List.of(), List.of(), List.of(), List.of());

    private static final TypeDefinition.Entity LINE = new TypeDefinition.Entity(
            "demo.Line", "line",
            new TypeDefinition.Prop.Id("id", "Long", "id", TypeDefinition.Prop.Id.GeneratedType.IDENTITY),
            null,
            List.of(new TypeDefinition.Prop.Scalar("qty", "Integer", "qty", true)),
            List.of(), List.of(), List.of(), List.of());

    private static final TypeDefinition.Aggregate BOOK = new TypeDefinition.Aggregate(
            "demo.Book", "book",
            new TypeDefinition.Prop.Id("id", "Long", "id", TypeDefinition.Prop.Id.GeneratedType.IDENTITY),
            new TypeDefinition.Prop.Version("version", "long", "version"),
            List.of(new TypeDefinition.Prop.Scalar("title", "String", "title", true)),
            List.of(),
            List.of(new TypeDefinition.Prop.Association(
                    "author", "demo.Author", AUTHOR, "author_id", false, false, null)),
            List.of(new TypeDefinition.Prop.EntityCollection(
                    "lines", "java.util.List", LINE, "book_id", null)),
            List.of());

    private static final TypeDefinition.Aggregate CUSTOMER = new TypeDefinition.Aggregate(
            "demo.Customer", "customer",
            new TypeDefinition.Prop.Id("id", "Long", "id", TypeDefinition.Prop.Id.GeneratedType.IDENTITY),
            null,
            List.of(new TypeDefinition.Prop.Scalar("name", "String", "name", true)),
            List.of(new TypeDefinition.Prop.Embedded(
                    "address", "demo.Address", ADDRESS, "address")),
            List.of(),
            List.of(),
            List.of(new TypeDefinition.Prop.ElementCollection(
                    "tags", "java.util.List", TAG, "customer_tags", "customer_id")));

    // ── TableSchema: the table's physical columns ─────────────────────────

    @Test
    void columnsListOwnPhysicalColumnsInDeclarationOrder() {
        List<ColumnRef> columns = BOOK.columns();

        // note: columns() reports the association's FK as a plain Simple column - the FK-ness
        // is only distinguishable through columnRefs()/resolve(); the PathResolver extraction
        // (issue #18) should normalize this
        assertEquals(List.of(
                new ColumnRef.Simple("id", "book"),
                new ColumnRef.Simple("version", "book"),
                new ColumnRef.Simple("title", "book"),
                new ColumnRef.Simple("author_id", "book")), columns);
    }

    @Test
    void embeddedLeavesFlattenWithUnderscorePrefix() {
        List<ColumnRef> columns = CUSTOMER.columns();

        assertEquals(List.of(
                new ColumnRef.Simple("id", "customer"),
                new ColumnRef.Simple("name", "customer"),
                new ColumnRef.Simple("address_street", "customer"),
                new ColumnRef.Simple("address_zipcode", "customer")), columns);
    }

    @Test
    void toManyCollectionsContributeNoColumnsToTheOwningTable() {
        assertTrue(BOOK.columns().stream().noneMatch(ref -> ref.table().equals("line")));
        assertTrue(CUSTOMER.columns().stream().noneMatch(ref -> ref.table().equals("customer_tags")));
    }

    @Test
    void valueObjectColumnsCarryANullTable() {
        // a VO has no table of its own: its (virtual) flattened columns list with a null owner
        assertEquals(List.of(
                new ColumnRef.Simple("street", null),
                new ColumnRef.Simple("zipcode", null)), ADDRESS.columns());
    }

    // ── Path resolution: the path-keyed index of OWN columns ──────────────

    @Test
    void columnRefsKeyOwnColumnsByLogicalPath() {
        var refs = CUSTOMER.columnRefs();

        assertEquals(new ColumnRef.Simple("id", "customer"), refs.get("id"));
        assertEquals(new ColumnRef.Simple("name", "customer"), refs.get("name"));
        // quirk pinned: embedded leaves carry a null ownerTable - columnRefsRecursive does not
        // pass the root table down into the embedded definition, unlike columns() (which does);
        // resolve() is correct too - the PathResolver extraction (issue #18) should normalize
        assertEquals(new ColumnRef.Simple("address_street", null), refs.get("address.street"));
        assertEquals(new ColumnRef.Simple("address_zipcode", null), refs.get("address.zipcode"));
        assertEquals(4, refs.size());
    }

    @Test
    void associationPathIndexesTheForeignKey() {
        var refs = BOOK.columnRefs();

        assertEquals(new ColumnRef.ForeignKey("author_id", "book"), refs.get("author"));
    }

    @Test
    void aColumnReachedAcrossARelationIsNotInThisIndex() {
        // a map keyed by path cannot carry the edge - cross-relation columns live in resolve() only
        assertFalse(BOOK.columnRefs().containsKey("author.name"));
        assertFalse(BOOK.columnRefs().containsKey("lines.qty"));
    }

    // ── Path resolution: resolve(path) ─────────────────────────────────────

    @Test
    void resolveScalarOnTheRootTable() {
        ColumnPlacement placement = BOOK.resolve("title");

        assertEquals(new ColumnRef.Simple("title", "book"), placement.leaf());
        assertEquals(List.of(), placement.edges());
    }

    @Test
    void resolveEmbeddedLeafWithPrefix() {
        ColumnPlacement placement = CUSTOMER.resolve("address.zipcode");

        assertEquals(new ColumnRef.Simple("address_zipcode", "customer"), placement.leaf());
        assertEquals(List.of(), placement.edges());
    }

    @Test
    void resolveToOneAssociationYieldsItsForeignKeyWithoutEdges() {
        ColumnPlacement placement = BOOK.resolve("author");

        // the FK lives on the ROOT table - the column is already reachable without a join,
        // so the placement carries no edges (unlike resolving THROUGH the association)
        assertEquals(new ColumnRef.ForeignKey("author_id", "book"), placement.leaf());
        assertEquals(List.of(), placement.edges());
    }

    @Test
    void resolveThroughAssociationCarriesTheEdgeAndTheAuthorsColumn() {
        ColumnPlacement placement = BOOK.resolve("author.name");

        assertEquals(new ColumnRef.Simple("name", "author"), placement.leaf());
        assertEquals(List.of(new JoinEdge.ToOne(
                new ColumnRef.ForeignKey("author_id", "book"),
                new ColumnRef.Simple("id", "author"))), placement.edges());
    }

    @Test
    void resolveThroughEntityCollectionPlacesTheLeafOnTheChildTable() {
        ColumnPlacement placement = BOOK.resolve("lines.qty");

        assertEquals(new ColumnRef.Simple("qty", "line"), placement.leaf());
        assertEquals(List.of(new JoinEdge.ToMany(
                new ColumnRef.Simple("id", "book"),
                new ColumnRef.ForeignKey("book_id", "line"))), placement.edges());
    }

    @Test
    void resolveThroughElementCollectionPlacesTheLeafOnTheChildTable() {
        ColumnPlacement placement = CUSTOMER.resolve("tags.label");

        assertEquals(new ColumnRef.Simple("label", "customer_tags"), placement.leaf());
        assertEquals(List.of(new JoinEdge.ToMany(
                new ColumnRef.Simple("id", "customer"),
                new ColumnRef.ForeignKey("customer_id", "customer_tags"))), placement.edges());
    }

    @Test
    void resolveRejectsUnknownAndUnnavigablePaths() {
        assertThrows(UnknownAttributePathException.class, () -> BOOK.resolve("nope"));
        assertThrows(UnknownAttributePathException.class, () -> BOOK.resolve("title.xyz"),
                "a scalar cannot be navigated through");
        assertThrows(UnknownAttributePathException.class, () -> BOOK.resolve("lines"),
                "a collection terminal is not a column");
    }

    // ── Classification helpers ────────────────────────────────────────────

    @Test
    void isToManyPathClassifiesTheHeadSegment() {
        assertTrue(BOOK.isToManyPath("lines.qty"));
        assertTrue(BOOK.isToManyPath("lines"));
        assertFalse(BOOK.isToManyPath("author.name"));
        assertFalse(BOOK.isToManyPath("title"));
        assertTrue(CUSTOMER.isToManyPath("tags.label"));
    }

    @Test
    void identityAndLifecycleHelpers() {
        assertTrue(BOOK.hasId());
        assertTrue(BOOK.ownsAggregateLifecycle());
        assertEquals("id", BOOK.idColumn());

        assertTrue(AUTHOR.hasId());
        assertFalse(AUTHOR.ownsAggregateLifecycle());

        assertFalse(ADDRESS.hasId());
        assertFalse(ADDRESS.ownsAggregateLifecycle());
        assertNull(ADDRESS.idColumn());
    }

    @Test
    void idReturnsTheIdentifierForEntitiesAndAggregatesAndNullForValueObjects() {
        assertEquals("id", BOOK.id().name());
        assertEquals("id", AUTHOR.id().name());
        assertNull(ADDRESS.id());
        assertNull(TAG.id());
    }

    @Test
    void findPropertyLooksAcrossCategories() {
        assertEquals("id", BOOK.findProperty("id").name());
        assertEquals("title", BOOK.findProperty("title").name());
        assertEquals("author", BOOK.findProperty("author").name());
        assertEquals("lines", BOOK.findProperty("lines").name());
        assertNull(BOOK.findProperty("nope"));
        assertEquals("label", TAG.findProperty("label").name());
    }

    @Test
    void propVisitorDispatchesEveryKindInDeclarationOrder() {
        List<String> visited = new ArrayList<>();
        TypeDefinition.Prop.Visitor<Void> recorder = new TypeDefinition.Prop.Visitor<>() {
            @Override public Void visitScalar(TypeDefinition.Prop.Scalar p) { visited.add("Scalar"); return null; }
            @Override public Void visitId(TypeDefinition.Prop.Id p) { visited.add("Id"); return null; }
            @Override public Void visitVersion(TypeDefinition.Prop.Version p) { visited.add("Version"); return null; }
            @Override public Void visitAssociation(TypeDefinition.Prop.Association p) { visited.add("Association"); return null; }
            @Override public Void visitEmbedded(TypeDefinition.Prop.Embedded p) { visited.add("Embedded"); return null; }
            @Override public Void visitEntityCollection(TypeDefinition.Prop.EntityCollection p) { visited.add("EntityCollection"); return null; }
            @Override public Void visitElementCollection(TypeDefinition.Prop.ElementCollection p) { visited.add("ElementCollection"); return null; }
        };

        for (TypeDefinition.Prop prop : BOOK.properties()) {
            prop.accept(recorder);
        }
        assertEquals(List.of("Id", "Version", "Scalar", "Association", "EntityCollection"), visited);

        visited.clear();
        for (TypeDefinition.Prop prop : CUSTOMER.properties()) {
            prop.accept(recorder);
        }
        assertEquals(List.of("Id", "Scalar", "Embedded", "ElementCollection"), visited);
    }

    @Test
    void typeDefinitionVisitorDispatchesByKind() {
        TypeDefinition.Visitor<String> visitor = new TypeDefinition.Visitor<>() {
            @Override public String visitEntity(TypeDefinition.Entity e) { return "Entity:" + e.typeName(); }
            @Override public String visitAggregate(TypeDefinition.Aggregate a) { return "Aggregate:" + a.typeName(); }
            @Override public String visitValueObject(TypeDefinition.ValueObject v) { return "ValueObject:" + v.typeName(); }
        };
        assertEquals("Aggregate:demo.Book", BOOK.accept(visitor));
        assertEquals("Entity:demo.Author", AUTHOR.accept(visitor));
        assertEquals("ValueObject:demo.Address", ADDRESS.accept(visitor));
    }

    @Test
    void asEntityReturnsTheEntityView() {
        assertEquals(AUTHOR, AUTHOR.asEntity());

        TypeDefinition.Entity view = BOOK.asEntity();
        assertEquals("demo.Book", view.typeName());
        assertEquals("book", view.tableName());
        assertEquals(BOOK.id(), view.id());
        assertEquals(BOOK.version(), view.version());
        assertEquals(BOOK.scalars(), view.scalars());
        assertEquals(BOOK.embedded(), view.embedded());
        assertTrue(view.associations().isEmpty());
        assertTrue(view.entityCollections().isEmpty());
        assertTrue(view.elementCollections().isEmpty());

        assertNull(ADDRESS.asEntity());
    }
}
