package io.github.fludakit.data.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.metadata.UnknownAttributePathException;

import static org.junit.jupiter.api.Assertions.*;

class TypeMappingTest {

    private static EmbeddedValueObjectMapping<?> geo() {
        return new EmbeddedValueObjectMapping("test.Geo", List.of(
                new Prop.Scalar("lat", "lat", "java.lang.Double"),
                new Prop.Scalar("lng", "lng", "java.lang.Double")));
    }

    private static AggregateTypeMapping<?> customer() {
        return new AggregateTypeMapping("test.Customer", "customer",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(new Prop.Scalar("name", "name", "java.lang.String")));
    }

    @Test
    void columnsFlattenInDeclarationOrder() {
        TypeMapping<?> order = new EntityTypeMapping("test.Order", "order_table",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(
                        new Prop.Scalar("status", "status", "java.lang.String"),
                        new Prop.Embedded("address", geo()),
                        new Prop.Reference("customer", "customer_id", "test.Customer", customer(), true, false)));
        List<String> cols = order.columns().stream().map(ColumnRef::column).toList();
        assertEquals(List.of("id", "status", "address_lat", "address_lng", "customer_id"), cols);
        assertEquals(List.of("id", "status", "address.lat", "address.lng", "customer"),
                List.copyOf(order.columnRefs().keySet()).subList(0, 5));
    }

    @Test
    void relationPropsContributeNoColumnAndElementCollectionKeepsChildPlacement() {
        ElementCollectionValueObjectMapping<?> tags = new ElementCollectionValueObjectMapping("test.Tags", "load_tags", "load_id",
                List.of(new Prop.Scalar("label", "label", "java.lang.String")));
        Prop.ElementCollection elementCollection = new Prop.ElementCollection("tags", tags, tags.columns());
        assertEquals("load_tags", elementCollection.childTable());
        assertEquals("load_id", elementCollection.parentFkColumn());
        assertEquals("test.Tags", elementCollection.elementTypeName());

        EntityTypeMapping<?> load = new EntityTypeMapping("test.Load", "load",
                new Prop.Id("id", "id", "java.lang.Long"), List.of(elementCollection));
        assertEquals(List.of("id"), load.columns().stream().map(ColumnRef::column).toList());
    }

    @Test
    void columnRefsKeepLogicalPathKeysSeparateFromPhysicalPlacement() {
        EntityTypeMapping<?> line = new EntityTypeMapping("test.Line", "line",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(new Prop.Scalar("sku", "sku", "java.lang.String")));
        ElementCollectionValueObjectMapping<?> tags = new ElementCollectionValueObjectMapping("test.Tag", "order_tags", "order_id",
                List.of(new Prop.Scalar("label", "label", "java.lang.String")));
        TypeMapping<?> order = new AggregateTypeMapping("test.Order", "orders",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(new Prop.Scalar("status", "status", "java.lang.String"),
                        new Prop.Embedded("address", geo()),
                        new Prop.Reference("customer", "customer_id", "test.Customer", customer(), false, false),
                        new Prop.EntityCollection("lines", line, "order", "order_id"),
                        new Prop.ElementCollection("tags", tags, tags.columns())));

        Map<String, ColumnRef> refs = order.columnRefs();
        assertEquals(new ColumnRef.Simple("address_lat", "orders"), refs.get("address.lat"));

        // A traversing path owns no local column: resolve() reports the relation edges
        // outermost-first together with the terminal leaf, as ONE value.
        ColumnPlacement customerName = order.resolve("customer.name");
        assertEquals(List.of(new JoinEdge.ToOne(new ColumnRef.ForeignKey("customer_id", "orders"),
                                     new ColumnRef.Simple("id", "customer"))),
                customerName.edges());
        assertEquals(new ColumnRef.Simple("name", "customer"), customerName.leaf());

        ColumnPlacement lineSku = order.resolve("lines.sku");
        assertEquals(List.of(new JoinEdge.ToMany(new ColumnRef.Simple("id", "orders"),
                                     new ColumnRef.ForeignKey("order_id", "line"))), lineSku.edges());
        assertEquals(new ColumnRef.Simple("sku", "line"), lineSku.leaf());

        ColumnPlacement tagLabel = order.resolve("tags.label");
        assertEquals(List.of(new JoinEdge.ToMany(new ColumnRef.Simple("id", "orders"),
                                     new ColumnRef.ForeignKey("order_id", "order_tags"))), tagLabel.edges());
        assertEquals(new ColumnRef.Simple("label", "order_tags"), tagLabel.leaf());

        assertTrue(order.isToManyPath("lines.sku"));
        assertTrue(order.isToManyPath("tags.label"));
    }

    @Test
    void derivedReferenceAccessors() {
        Prop.Reference ref = new Prop.Reference("customer", "customer_id", "test.Customer", customer(), false, false);
        assertEquals("customer", ref.name());
        assertEquals("test.Customer", ref.targetTypeName());
        assertEquals("customer", ref.targetTable());
        assertEquals("id", ref.targetIdColumn());
        assertNull(new Prop.Reference("customer", "customer_id", "test.Customer", null, false, false).targetTable());
    }

    @Test
    void stereotypeAndToManyAndLookups() {
        TypeMapping<?> parent = new AggregateTypeMapping("test.Parent", "parent",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(new Prop.EntityCollection("children", customer(), "owner", "owner_id")));
        assertTrue(parent.isToManyPath("children.id"));
        assertFalse(parent.isToManyPath("id"));
        assertFalse(parent.isToManyPath("unknown"));
        assertTrue(parent.ownsAggregateLifecycle());
        assertSame(parent.id(), parent.prop("id"));
        assertEquals("parent", parent.tableName());

        EmbeddedValueObjectMapping<?> embedded = geo();
        assertNull(embedded.tableName());
        assertNull(embedded.id());
        assertTrue(embedded.isValueObject());
        assertFalse(embedded.tableBacked());
        UnknownAttributePathException unknown = assertThrows(UnknownAttributePathException.class,
                () -> parent.prop("nope"));
        assertEquals("nope", unknown.path());

        EntityTypeMapping<?> unresolved = new EntityTypeMapping("test.X", "x", null, List.of(
                new Prop.Embedded("a", null)));
        IllegalStateException e = assertThrows(IllegalStateException.class, unresolved::columns);
        assertTrue(e.getMessage().contains("target is unresolved"), e.getMessage());
    }
}
