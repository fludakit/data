package io.github.fludakit.data.model;

import io.github.fludakit.data.metadata.ColumnRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.fludakit.data.model.DataModel.Prop;
import static io.github.fludakit.data.model.DataModel.Stereotype;
import static org.junit.jupiter.api.Assertions.*;

class DataModelTest {

    private static EmbeddedValueObjectModel geo() {
        return new EmbeddedValueObjectModel("test.Geo", List.of(
                new Prop.Scalar("lat", "lat", "java.lang.Double"),
                new Prop.Scalar("lng", "lng", "java.lang.Double")));
    }

    private static EntityDataModel customer() {
        return new EntityDataModel(Stereotype.AGGREGATE_ROOT, "test.Customer", "customer",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(new Prop.Scalar("name", "name", "java.lang.String")));
    }

    @Test
    void columnsFlattenInDeclarationOrder() {
        EntityDataModel order = new EntityDataModel(Stereotype.ENTITY, "test.Order", "order_table",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(
                        new Prop.Scalar("status", "status", "java.lang.String"),
                        new Prop.Embedded("address", "address", geo()),
                        new Prop.Reference("customer", "customer_id", "test.Customer", customer(), true)));
        List<String> paths = order.columns().stream().map(ColumnRef::path).toList();
        assertEquals(List.of("id", "status", "address.lat", "address.lng", "customer"), paths);
        List<String> cols = order.columns().stream().map(ColumnRef::column).toList();
        assertEquals(List.of("id", "status", "address_lat", "address_lng", "customer_id"), cols);
    }

    @Test
    void relationPropsContributeNoColumnAndElementListKeepsChildPlacement() {
        ExternalValueObjectModel tags = new ExternalValueObjectModel("test.Tags", "load_tags", "load_id",
                List.of(new Prop.Scalar("label", "label", "java.lang.String")));
        Prop.ElementList elementList = new Prop.ElementList("tags", tags, tags.columns());
        assertEquals("load_tags", elementList.childTable());
        assertEquals("load_id", elementList.parentFkColumn());
        assertEquals("test.Tags", elementList.elementTypeName());

        EntityDataModel load = new EntityDataModel(Stereotype.ENTITY, "test.Load", "load",
                new Prop.Id("id", "id", "java.lang.Long"), List.of(elementList));
        assertEquals(List.of("id"), load.columns().stream().map(ColumnRef::path).toList());
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
        EntityDataModel parent = new EntityDataModel(Stereotype.AGGREGATE_ROOT, "test.Parent", "parent",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(new Prop.EntityList("children", customer(), "owner", "owner_id")));
        assertTrue(parent.isToManyHead("children"));
        assertFalse(parent.isToManyHead("id"));
        assertFalse(parent.isToManyHead("unknown"));
        assertEquals(Stereotype.AGGREGATE_ROOT, parent.stereotype());
        assertSame(parent.id(), parent.prop("id"));
        assertEquals("parent", parent.tableName());
        assertFalse(parent.isValueObject());

        EmbeddedValueObjectModel embedded = geo();
        assertNull(embedded.tableName());
        assertNull(embedded.id());
        assertTrue(embedded.isValueObject());
        assertEquals(Stereotype.VALUE_OBJECT, embedded.stereotype());
        assertThrows(IllegalArgumentException.class, () -> parent.prop("nope"));

        EntityDataModel unresolved = new EntityDataModel(Stereotype.ENTITY, "test.X", "x", null, List.of(
                new Prop.Embedded("a", "a", null)));
        IllegalStateException e = assertThrows(IllegalStateException.class, unresolved::columns);
        assertTrue(e.getMessage().contains("target is unresolved"), e.getMessage());
    }
}
