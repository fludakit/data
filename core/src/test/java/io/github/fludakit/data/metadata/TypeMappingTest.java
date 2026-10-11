package io.github.fludakit.data.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import io.github.fludakit.data.metadata.TypeMapping.Kind;
import io.github.fludakit.data.metadata.TypeMapping.Prop;

import static org.junit.jupiter.api.Assertions.*;

class TypeMappingTest {

    private static EmbeddedValueObjectMapping<?> geo() {
        return new EmbeddedValueObjectMapping("test.Geo", List.of(
                new Prop.Scalar("lat", "lat", "java.lang.Double"),
                new Prop.Scalar("lng", "lng", "java.lang.Double")));
    }

    private static EntityTypeMapping<?> customer() {
        return new EntityTypeMapping(Kind.AGGREGATE_ROOT, "test.Customer", "customer",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(new Prop.Scalar("name", "name", "java.lang.String")));
    }

    @Test
    void columnsFlattenInDeclarationOrder() {
        EntityTypeMapping<?> order = new EntityTypeMapping(Kind.ENTITY, "test.Order", "order_table",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(
                        new Prop.Scalar("status", "status", "java.lang.String"),
                        new Prop.Embedded("address", "address", geo()),
                        new Prop.Reference("customer", "customer_id", "test.Customer", customer(), true)));
        List<String> cols = order.columns().stream().map(ColumnRef::column).toList();
        assertEquals(List.of("id", "status", "address_lat", "address_lng", "customer_id"), cols);
        assertEquals(List.of("id", "status", "address.lat", "address.lng", "customer"),
                List.copyOf(order.columnRefs().keySet()).subList(0, 5));
    }

    @Test
    void relationPropsContributeNoColumnAndElementListKeepsChildPlacement() {
        ElementCollectionValueObjectMapping<?> tags = new ElementCollectionValueObjectMapping("test.Tags", "load_tags", "load_id",
                List.of(new Prop.Scalar("label", "label", "java.lang.String")));
        Prop.ElementList elementList = new Prop.ElementList("tags", tags, tags.columns());
        assertEquals("load_tags", elementList.childTable());
        assertEquals("load_id", elementList.parentFkColumn());
        assertEquals("test.Tags", elementList.elementTypeName());

        EntityTypeMapping<?> load = new EntityTypeMapping(Kind.ENTITY, "test.Load", "load",
                new Prop.Id("id", "id", "java.lang.Long"), List.of(elementList));
        assertEquals(List.of("id"), load.columns().stream().map(ColumnRef::column).toList());
    }

    @Test
    void columnRefsKeepLogicalPathKeysSeparateFromPhysicalPlacement() {
        EntityTypeMapping<?> line = new EntityTypeMapping(Kind.ENTITY, "test.Line", "line",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(new Prop.Scalar("sku", "sku", "java.lang.String")));
        ElementCollectionValueObjectMapping<?> tags = new ElementCollectionValueObjectMapping("test.Tag", "order_tags", "order_id",
                List.of(new Prop.Scalar("label", "label", "java.lang.String")));
        EntityTypeMapping<?> order = new EntityTypeMapping(Kind.AGGREGATE_ROOT, "test.Order", "orders",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(new Prop.Scalar("status", "status", "java.lang.String"),
                        new Prop.Embedded("address", "address", geo()),
                        new Prop.Reference("customer", "customer_id", "test.Customer", customer(), false),
                        new Prop.EntityList("lines", line, "order", "order_id"),
                        new Prop.ElementList("tags", tags, tags.columns())));

        Map<String, ColumnRef> refs = order.columnRefs();
        assertEquals(new ColumnRef("address_lat", "orders"), refs.get("address.lat"));
        assertEquals(new ColumnRef("name", "customer", "customer",
                List.of(new JoinRef("orders", null, "customer_id", "customer", "customer", "id"))),
                refs.get("customer.name"));
        assertEquals(new ColumnRef("sku", "line", "lines",
                List.of(new JoinRef("orders", null, "id", "line", "lines", "order_id"))),
                refs.get("lines.sku"));
        assertEquals(new ColumnRef("label", "order_tags", "tags",
                List.of(new JoinRef("orders", null, "id", "order_tags", "tags", "order_id"))),
                refs.get("tags.label"));
        assertEquals(refs.get("customer.name"), order.columnRef("customer.name"));
        assertTrue(order.isToManyPath("lines.sku"));
        assertTrue(order.isToManyPath("tags.label"));
    }

    @Test
    void derivedReferenceAccessors() {
        Prop.Reference ref = new Prop.Reference("customer", "customer_id", "test.Customer", customer(), false);
        assertEquals("customer", ref.name());
        assertEquals("test.Customer", ref.targetTypeName());
        assertEquals("customer", ref.targetTable());
        assertEquals("id", ref.targetIdColumn());
        assertNull(new Prop.Reference("customer", "customer_id", "test.Customer", null, false).targetTable());
    }

    @Test
    void stereotypeAndToManyAndLookups() {
        EntityTypeMapping<?> parent = new EntityTypeMapping(Kind.AGGREGATE_ROOT, "test.Parent", "parent",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(new Prop.EntityList("children", customer(), "owner", "owner_id")));
        assertTrue(parent.isToManyPath("children.id"));
        assertFalse(parent.isToManyPath("id"));
        assertFalse(parent.isToManyPath("unknown"));
        assertEquals(Kind.AGGREGATE_ROOT, parent.kind());
        assertSame(parent.id(), parent.prop("id"));
        assertEquals("parent", parent.tableName());
        assertFalse(parent.isValueObject());

        EmbeddedValueObjectMapping embedded = geo();
        assertNull(embedded.tableName());
        assertNull(embedded.id());
        assertTrue(embedded.isValueObject());
        assertEquals(Kind.VALUE_OBJECT, embedded.kind());
        assertThrows(IllegalArgumentException.class, () -> parent.prop("nope"));

        EntityTypeMapping<?> unresolved = new EntityTypeMapping(Kind.ENTITY, "test.X", "x", null, List.of(
                new Prop.Embedded("a", "a", null)));
        IllegalStateException e = assertThrows(IllegalStateException.class, unresolved::columns);
        assertTrue(e.getMessage().contains("target is unresolved"), e.getMessage());
    }
}
