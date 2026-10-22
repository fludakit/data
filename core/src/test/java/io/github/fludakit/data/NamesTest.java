package io.github.fludakit.data;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NamesTest {

    @Test
    void pluralizesRegularNouns() {
        assertEquals("orders", Names.pluralize("order"));
        assertEquals("customers", Names.pluralize("customer"));
        assertEquals("products", Names.pluralize("product"));
    }

    @Test
    void pluralizesConsonantY() {
        assertEquals("companies", Names.pluralize("company"));
        assertEquals("categories", Names.pluralize("category"));
    }

    @Test
    void pluralizesSibilantEndings() {
        assertEquals("boxes", Names.pluralize("box"));
        assertEquals("buses", Names.pluralize("bus"));
        assertEquals("churches", Names.pluralize("church"));
    }

    @Test
    void tableNameReturnsPlural() {
        assertEquals("orders", Names.tableName("Order"));
        assertEquals("customer_profiles", Names.tableName("CustomerProfile"));
    }
}
