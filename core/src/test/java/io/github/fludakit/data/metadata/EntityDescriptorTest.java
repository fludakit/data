package io.github.fludakit.data.metadata;

import io.github.fludakit.data.metadata.EntityDescriptor.Attribute;
import io.github.fludakit.data.metadata.EntityDescriptor.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Locks the {@link EntityDescriptor} interface defaults before the processor reclassification lands. */
class EntityDescriptorTest {

    record Fixture(String typeName, String tableName, Attribute id, List<Attribute> attributes)
            implements EntityDescriptor { }

    private static Attribute attribute(String name, Kind kind, String column) {
        return new Attribute(name, "java.lang.String", kind, column, null, null, null);
    }

    private static EntityDescriptor address() {
        return new Fixture("Address", null, null, List.of(
                attribute("street", Kind.SCALAR, "street"),
                attribute("city", Kind.SCALAR, "city")));
    }

    private static EntityDescriptor person(EntityDescriptor embeddedAddress) {
        return new Fixture("Person", "person",
                new Attribute("id", "java.lang.Long", Kind.ID, "id", null, null, null),
                List.of(
                        attribute("name", Kind.SCALAR, "name"),
                        new Attribute("address", "Address", Kind.EMBEDDED, "address",
                                "Address", null, embeddedAddress),
                        new Attribute("friends", "java.util.List<Friend>", Kind.ONE_TO_MANY, null,
                                "Friend", "bestFriend", null),
                        new Attribute("nicknames", "java.util.List<Nickname>", Kind.ELEMENT_COLLECTION, null,
                                "Nickname", null, null)));
    }

    @Test
    void attributeResolvesIdentifierFirstThenByName() {
        EntityDescriptor descriptor = person(address());

        assertThat(descriptor.attribute("id"))
                .isEqualTo(new Attribute("id", "java.lang.Long", Kind.ID, "id", null, null, null));
        assertThat(descriptor.attribute("name").kind()).isEqualTo(Kind.SCALAR);
        assertThat(descriptor.attribute("address").kind()).isEqualTo(Kind.EMBEDDED);
    }

    @Test
    void attributeThrowsForUnknownPropertyNamingPropertyAndType() {
        EntityDescriptor descriptor = person(address());

        assertThatThrownBy(() -> descriptor.attribute("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("Person");
    }

    @Test
    void isValueObjectIsTrueIffTableNameIsNull() {
        assertThat(person(address()).isValueObject()).isFalse();
        assertThat(address().isValueObject()).isTrue();
    }

    @Test
    void columnsFlattenEmbeddedLeavesWithPrefixColumnAndDotPath() {
        List<EntityDescriptor.ColumnRef> columns = person(address()).columns();

        assertThat(columns).containsExactly(
                new EntityDescriptor.ColumnRef("id", "id"),
                new EntityDescriptor.ColumnRef("name", "name"),
                new EntityDescriptor.ColumnRef("address_street", "address.street"),
                new EntityDescriptor.ColumnRef("address_city", "address.city"));
    }

    @Test
    void columnsThrowForEmbeddedWithoutResolvedDescriptor() {
        EntityDescriptor descriptor = person(null);

        assertThatThrownBy(descriptor::columns)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("address")
                .hasMessageContaining("Person");
    }

    @Test
    void oneToManyAndElementCollectionContributeNoColumn() {
        List<EntityDescriptor.ColumnRef> columns = person(address()).columns();

        assertThat(columns).extracting(EntityDescriptor.ColumnRef::column)
                .doesNotContain("friends", "nicknames", "friend_id", "nickname_id");
    }
}
