package io.github.fludakit.data.metadata;

import io.github.fludakit.data.metamodel.ElementCollectionAttributeRecord;
import io.github.fludakit.data.metamodel.EmbeddedAttributeRecord;
import io.github.fludakit.data.metamodel.ForeignKeyAttributeRecord;
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
                        new OneToManyAttributeRecord<Person, Friend>("friends", Friend.class, "bestFriend",
                                () -> null),
                        new ElementCollectionAttributeRecord<Person, String>("nicknames", String.class,
                                "person_nicknames", "person_id", () -> null)));
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

    static class Parent { }

    static class Child { }

    static class Note { }

    record WithColumns<T>(Class<T> entityClass, String tableName, IdAttribute<T> id,
                          List<Attribute<T>> attributes, List<ColumnRef> columns)
            implements EntityDescriptor<T> { }

    static EntityDescriptor<Child> childMetadata() {
        return new WithColumns<>(Child.class, "rel.child",
                new IdAttributeRecord<>("id", "id"),
                List.of(new TextColumnAttributeRecord<Child>("label", "label")),
                List.of(new ColumnRef("id", "id", "rel.child", List.of()),
                        new ColumnRef("label", "label", "rel.child", List.of()),
                        new ColumnRef("seq", "seq", "rel.child", List.of())));
    }

    static EntityDescriptor<Note> noteMetadata() {
        return new WithColumns<>(Note.class, null, null, List.of(),
                List.of(new ColumnRef("text", "text")));
    }

    static EntityDescriptor<Parent> parentMetadata(EntityDescriptor<Child> child,
                                                   EntityDescriptor<Note> note) {
        return new Fixture<>(Parent.class, "rel.parent",
                new IdAttributeRecord<>("id", "id"),
                List.of(
                        new TextColumnAttributeRecord<Parent>("name", "name"),
                        new TextColumnAttributeRecord<Parent>("address.city", "address_city"),
                        new ForeignKeyAttributeRecord<Parent, Child>("primary", "primary_id", Child.class,
                                () -> child),
                        new OneToManyAttributeRecord<Parent, Child>("children", Child.class, "owner",
                                () -> child),
                        new ElementCollectionAttributeRecord<Parent, Note>("notes", Note.class,
                                "parent_notes", "parent_id", () -> note)));
    }

    @Test
    void columnRefResolvesLocalAndFlattenedEmbeddedPathsAsPlainColumns() {
        EntityDescriptor<Parent> descriptor = parentMetadata(childMetadata(), noteMetadata());

        ColumnRef local = descriptor.columnRef("name");
        assertThat(local.column()).isEqualTo("name");
        assertThat(local.table()).isNull();
        assertThat(local.joins()).isEmpty();

        ColumnRef embedded = descriptor.columnRef("address.city");
        assertThat(embedded.column()).isEqualTo("address_city");
    }

    @Test
    void columnRefThroughForeignKeyCarriesTargetTableAndJoin() {
        ColumnRef viaFk = parentMetadata(childMetadata(), noteMetadata()).columnRef("primary.label");

        assertThat(viaFk.column()).isEqualTo("label");
        assertThat(viaFk.table()).isEqualTo("rel.child");
        assertThat(viaFk.joins())
                .containsExactly(new JoinRef("rel.child", "primary", "primary_id", "id"));
    }

    @Test
    void columnRefThroughOneToManyJoinsOnDerivedChildFkColumnAndKeepsFullPath() {
        ColumnRef viaOtm = parentMetadata(childMetadata(), noteMetadata()).columnRef("children.seq");

        assertThat(viaOtm.joins())
                .containsExactly(new JoinRef("rel.child", "children", "id", "owner_id"));
        assertThat(viaOtm.path()).isEqualTo("children.seq");
    }

    @Test
    void columnRefThroughElementCollectionJoinsChildTableOnParentFkColumn() {
        ColumnRef viaEc = parentMetadata(childMetadata(), noteMetadata()).columnRef("notes.text");

        assertThat(viaEc.joins())
                .containsExactly(new JoinRef("parent_notes", "notes", "id", "parent_id"));
    }

    @Test
    void columnRefFailsFastForUnmappedRelationTargetSupplier() {
        EntityDescriptor<Parent> descriptor = new Fixture<>(Parent.class, "rel.parent",
                new IdAttributeRecord<>("id", "id"),
                List.of(new ForeignKeyAttributeRecord<Parent, Child>(
                        "primary", "primary_id", Child.class, () -> null)));

        assertThatThrownBy(() -> descriptor.columnRef("primary.label"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("primary")
                .hasMessageContaining("unmapped");
    }

    @Test
    void columnRefRejectsUnknownHeadAndNonNavigableHead() {
        EntityDescriptor<Parent> descriptor = parentMetadata(childMetadata(), noteMetadata());

        assertThatThrownBy(() -> descriptor.columnRef("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown property 'nope'");
        assertThatThrownBy(() -> descriptor.columnRef("name.x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support path navigation");
    }
}
