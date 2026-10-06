package io.github.fludakit.data.metadata;

import io.github.fludakit.data.metamodel.ElementCollectionAttributeRecord;
import io.github.fludakit.data.metamodel.EmbeddedAttributeRecord;
import io.github.fludakit.data.metamodel.IdAttribute;
import io.github.fludakit.data.metamodel.IdAttributeRecord;
import io.github.fludakit.data.metamodel.OneToManyAttributeRecord;
import io.github.fludakit.data.metamodel.SortableColumnAttributeRecord;
import io.github.fludakit.data.metamodel.TextColumnAttributeRecord;
import jakarta.data.Sort;
import jakarta.data.metamodel.Attribute;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Locks the subtype-based {@link EntityDescriptor} contract: flat leaf resolution, column view, relation accessors. */
class EntityDescriptorTest {

    static class Person { }

    static class Friend { }

    static class Address { }

    record Fixture<T>(Class<T> entityClass, String tableName, IdAttribute<T> id,
                      List<Attribute<T>> attributes) implements EntityDescriptor<T> { }

    static EntityDescriptor<Address> address() {
        return new Fixture<>(Address.class, null, null, List.of(
                new TextColumnAttributeRecord<Address>("street", "street"),
                new TextColumnAttributeRecord<Address>("city", "city")));
    }

    /** Person mirrors the generated shape: container followed by its flattened leaves. */
    static EntityDescriptor<Person> person(EntityDescriptor<Address> embeddedAddress) {
        return new Fixture<>(Person.class, "person",
                new IdAttributeRecord<>("id", "id"),
                List.of(
                        new TextColumnAttributeRecord<Person>("name", "name"),
                        new EmbeddedAttributeRecord<Person, Address>("address", embeddedAddress),
                        new TextColumnAttributeRecord<Person>("address.street", "address_street"),
                        new SortableColumnAttributeRecord<Person>("address.zip", "address_zip"),
                        new OneToManyAttributeRecord<Person, Friend>("friends", Friend.class, "bestFriend"),
                        new ElementCollectionAttributeRecord<Person, String>("nicknames", String.class)));
    }

    @Test
    void attributeResolvesIdentifierFirstThenByName() {
        EntityDescriptor<Person> descriptor = person(address());

        assertThat(descriptor.attribute("id")).isEqualTo(new IdAttributeRecord<Person>("id", "id"));
        assertThat(descriptor.attribute("name").name()).isEqualTo("name");
        assertThat(descriptor.attribute("address")).isInstanceOf(
                io.github.fludakit.data.metamodel.EmbeddedAttribute.class);
    }

    @Test
    void dotPathResolvesFlattenedEmbeddedLeaf() {
        EntityDescriptor<Person> descriptor = person(address());

        assertThat(descriptor.attribute("address.street")).isEqualTo(
                new TextColumnAttributeRecord<Person>("address.street", "address_street"));
    }

    @Test
    void attributeThrowsForUnknownPropertyNamingPropertyAndType() {
        EntityDescriptor<Person> descriptor = person(address());

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
    void columnsFlattenIdScalarsAndLeavesInDeclarationOrder() {
        List<ColumnRef> columns = person(address()).columns();

        assertThat(columns).containsExactly(
                new ColumnRef("id", "id"),
                new ColumnRef("name", "name"),
                new ColumnRef("address_street", "address.street"),
                new ColumnRef("address_zip", "address.zip"));
    }

    @Test
    void columnsThrowForEmbeddedWithoutResolvedDescriptor() {
        EntityDescriptor<Person> descriptor = person(null);

        assertThatThrownBy(descriptor::columns)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("address")
                .hasMessageContaining("Person");
    }

    @Test
    void oneToManyAndElementCollectionContributeNoColumn() {
        List<ColumnRef> columns = person(address()).columns();

        assertThat(columns).extracting(ColumnRef::column)
                .doesNotContain("friends", "nicknames", "friend_id", "nickname_id");
    }

    @Test
    void relationAccessorsExposeTargetAndInverse() {
        EntityDescriptor<Person> descriptor = person(address());

        assertThat(descriptor.attribute("friends")).isInstanceOf(
                io.github.fludakit.data.metamodel.OneToManyAttribute.class);
        assertThat(descriptor.attribute("nicknames")).isInstanceOf(
                io.github.fludakit.data.metamodel.ElementCollectionAttribute.class);
        assertThat(descriptor.attribute("address")).isInstanceOf(
                io.github.fludakit.data.metamodel.EmbeddedAttribute.class);
    }

    @Test
    void sortableAttributesEmitJakartaSortsKeyedByAttributeName() {
        EntityDescriptor<Person> descriptor = person(address());

        Sort<Person> byZip = ((io.github.fludakit.data.metamodel.SortableColumnAttribute<Person>)
                descriptor.attribute("address.zip")).asc();
        assertThat(byZip.property()).isEqualTo("address.zip");
    }
}
