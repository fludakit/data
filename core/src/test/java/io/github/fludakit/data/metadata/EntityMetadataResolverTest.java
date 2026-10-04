package io.github.fludakit.data.metadata;

import io.github.fludakit.data.Names;
import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Identity;
import org.jmolecules.ddd.annotation.ValueObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntityMetadataResolverTest {

    @AggregateRoot
    static class Customer {
        @Identity Long id;
        String name;
        String email;
    }

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
}
