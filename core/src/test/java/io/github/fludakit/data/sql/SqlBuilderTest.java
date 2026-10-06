package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.EntityDescriptor;
import io.github.fludakit.data.metadata.EntityDescriptor.Attribute;
import io.github.fludakit.data.metadata.EntityDescriptor.Kind;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlBuilderTest {

    record Fixture(String typeName, String tableName, Attribute id, List<Attribute> attributes)
            implements EntityDescriptor { }

    static EntityDescriptor customer() {
        return new Fixture("Customer", "customer",
                new Attribute("id", "java.lang.Long", Kind.ID, "id", null, null, null),
                List.of(new Attribute("name", "java.lang.String", Kind.SCALAR, "name", null, null, null)));
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
        EntityDescriptor profile = new Fixture("Profile", "profile",
                new Attribute("id", "java.lang.Long", Kind.ID, "id", null, null, null),
                List.of(new Attribute("address", "gp.Address", Kind.EMBEDDED, "address", null, null,
                        new Fixture("Address", null, null, List.of(
                                new Attribute("city", "java.lang.String", Kind.SCALAR, "city", null, null, null))))));
        String sql = new SqlBuilder().selectBy(profile, List.of(),
                List.of(SortSpec.of("address.city", true, false)), 0, null);
        assertThat(sql).endsWith("ORDER BY \"address_city\" ASC");
        assertThat(SqlBuilder.resolveColumn(profile, "address.city")).isEqualTo("address_city");
    }

    @Test
    void orderByRejectsUnknownProperty() {
        assertThatThrownBy(() -> new SqlBuilder().orderBy(customer(), List.of(SortSpec.of("nope", true, false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown sort property: nope");
    }

    @Test
    void rendersInsert() {
        assertThat(new SqlBuilder().insert(customer()))
                .isEqualTo("INSERT INTO \"customer\" (\"id\", \"name\") VALUES (:id, :name)");
    }
}
