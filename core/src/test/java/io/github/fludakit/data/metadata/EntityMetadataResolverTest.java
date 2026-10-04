package io.github.fludakit.data.metadata;

import io.github.fludakit.data.Names;
import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Entity;
import org.jmolecules.ddd.annotation.Identity;
import org.jmolecules.ddd.annotation.ValueObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntityMetadataResolverTest {

    @AggregateRoot
    static class Customer {
        @Identity Long id;
        String name;
        String email;
        List<Order> orders;
        Address address;
    }

    @Entity
    static class Order {
        @Identity Long id;
        Customer customer;
        List<Item> items;
    }

    @ValueObject
    record Item(String sku, int qty) {}

    @ValueObject
    record Address(String city) {}

    @ValueObject
    record Money(java.math.BigDecimal amount, String currency) {}

    @Test
    void resolvesEntityAndColumnsByConvention() {
        EntityMetadata m = new EntityMetadataResolver().resolve(Customer.class);
        assertThat(m.tableName()).isEqualTo("customer");
        assertThat(m.id().name()).isEqualTo("id");
        assertThat(m.id().column()).isEqualTo("id");
        assertThat(m.property("name").column()).isEqualTo("name");
        assertThat(m.property("email").column()).isEqualTo("email");
    }

    @Test
    void resolvesValueObject() {
        EntityMetadata m = new EntityMetadataResolver().resolve(Money.class);
        assertThat(m.isValueObject()).isTrue();
        assertThat(m.tableName()).isNull();
    }

    @Test
    void snakeCasesCamelNames() {
        assertThat(Names.toSnakeCase("orderItems")).isEqualTo("order_items");
        assertThat(Names.fkColumn("customer")).isEqualTo("customer_id");
        assertThat(Names.childTable("order", "orderItems")).isEqualTo("order_order_items");
    }

    @Test
    void resolvesManyToOneAndFindsInverseForOneToMany() {
        EntityMetadataResolver resolver = new EntityMetadataResolver();

        EntityMetadata order = resolver.resolve(Order.class);
        assertThat(order.property("customer").kind()).isEqualTo(EntityMetadata.Kind.MANY_TO_ONE);
        assertThat(order.property("customer").column()).isEqualTo("customer_id");
        assertThat(order.property("customer").target().tableName()).isEqualTo("customer");
        assertThat(order.propertiesOf(EntityMetadata.Kind.MANY_TO_ONE, Customer.class)).hasSize(1);

        EntityMetadata customer = resolver.resolve(Customer.class);
        assertThat(customer.property("orders").kind()).isEqualTo(EntityMetadata.Kind.ONE_TO_MANY);
        assertThat(customer.property("orders").inverse()).isNotNull();
        assertThat(customer.property("orders").inverse().column()).isEqualTo("customer_id");
    }

    @Test
    void resolvesOneToManyInverseWhenAggregateRootResolvedFirst() {
        EntityMetadataResolver resolver = new EntityMetadataResolver();

        EntityMetadata customer = resolver.resolve(Customer.class);
        EntityMetadata order = resolver.resolve(Order.class);

        assertThat(customer.property("orders").kind()).isEqualTo(EntityMetadata.Kind.ONE_TO_MANY);
        assertThat(customer.property("orders").inverse()).isNotNull();
        assertThat(customer.property("orders").inverse().column()).isEqualTo("customer_id");
        assertThat(order.property("customer").target().tableName()).isEqualTo("customer");
    }

    @Test
    void resolvesElementCollectionAndEmbeddedColumns() {
        EntityMetadataResolver resolver = new EntityMetadataResolver();

        EntityMetadata order = resolver.resolve(Order.class);
        assertThat(order.property("items").kind()).isEqualTo(EntityMetadata.Kind.ELEMENT_COLLECTION);
        assertThat(order.property("items").target().type()).isEqualTo(Item.class);

        EntityMetadata customer = resolver.resolve(Customer.class);
        assertThat(customer.property("address").kind()).isEqualTo(EntityMetadata.Kind.EMBEDDED);
        assertThat(customer.columns())
                .anySatisfy(c -> {
                    assertThat(c.column()).isEqualTo("address_city");
                    assertThat(c.path()).isEqualTo("address.city");
                });
    }
}
