package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.EntityDescriptor;
import io.github.fludakit.data.metamodel.EmbeddedAttributeRecord;
import io.github.fludakit.data.metamodel.IdAttributeRecord;
import io.github.fludakit.data.metamodel.TextColumnAttributeRecord;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;
import jakarta.data.metamodel.Attribute;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlBuilderTest {

    static class Customer { }

    static class Profile { }

    static class Address { }

    static EntityDescriptor<Customer> customer() {
        return new EntityDescriptor<>() {
            @Override public Class<Customer> entityClass() { return Customer.class; }
            @Override public String tableName() { return "customer"; }
            @Override public IdAttributeRecord<Customer> id() {
                return new IdAttributeRecord<>("id", "id");
            }
            @Override public List<Attribute<Customer>> attributes() {
                return List.of(new TextColumnAttributeRecord<>("name", "name"));
            }
        };
    }

    /** Profile with an embedded Address: the flattened leaf is a direct attribute member. */
    static EntityDescriptor<Profile> profile() {
        EntityDescriptor<Address> address = new EntityDescriptor<>() {
            @Override public Class<Address> entityClass() { return Address.class; }
            @Override public String tableName() { return null; }
            @Override public io.github.fludakit.data.metamodel.IdAttribute<Address> id() { return null; }
            @Override public List<Attribute<Address>> attributes() {
                return List.of(new TextColumnAttributeRecord<>("city", "city"));
            }
        };
        return new EntityDescriptor<>() {
            @Override public Class<Profile> entityClass() { return Profile.class; }
            @Override public String tableName() { return "profile"; }
            @Override public IdAttributeRecord<Profile> id() { return new IdAttributeRecord<>("id", "id"); }
            @Override public List<Attribute<Profile>> attributes() {
                return List.of(
                        new EmbeddedAttributeRecord<Profile, Address>("address", address),
                        new TextColumnAttributeRecord<Profile>("address.city", "address_city"));
            }
        };
    }

    @Test
    void rendersSelectWithWhereAndOrder() {
        String sql = new SqlBuilder().selectBy(customer(),
                List.of(Predicate.of("name", Operator.EQ, "name")),
                List.of(SortSpec.of("id", true, false)), 0, null);
        assertThat(sql).isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" WHERE \"name\" = :name ORDER BY \"id\" ASC");
    }

    @Test
    void orderByResolvesEmbeddedDotPathToFlattenedColumn() {
        String sql = new SqlBuilder().selectBy(profile(), List.of(),
                List.of(SortSpec.of("address.city", true, false)), 0, null);
        assertThat(sql).endsWith("ORDER BY \"address_city\" ASC");
        assertThat(SqlBuilder.resolveColumn(profile(), "address.city")).isEqualTo("address_city");
    }

    @Test
    void orderByRejectsUnknownProperty() {
        assertThatThrownBy(() -> new SqlBuilder().orderBy(customer(), List.of(SortSpec.of("nope", true, false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown sort property: nope");
    }

    @Test
    void orderByRejectsEmbeddedContainerName() {
        // The container itself carries no column: sorting by "address" must not silently pass.
        assertThatThrownBy(() -> new SqlBuilder().orderBy(profile(), List.of(SortSpec.of("address", true, false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown sort property: address");
    }

    @Test
    void rendersInsert() {
        assertThat(new SqlBuilder().insert(customer()))
                .isEqualTo("INSERT INTO \"customer\" (\"id\", \"name\") VALUES (:id, :name)");
    }
}
