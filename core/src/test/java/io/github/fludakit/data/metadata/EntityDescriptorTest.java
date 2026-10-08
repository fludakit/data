package io.github.fludakit.data.metadata;

import io.github.fludakit.data.metamodel.EmbeddableAttribute;
import io.github.fludakit.data.metamodel.EmbeddableAttributeRecord;
import io.github.fludakit.data.metamodel.EntityAttributeRecord;
import io.github.fludakit.data.metamodel.IdAttribute;
import io.github.fludakit.data.metamodel.IdAttributeRecord;
import io.github.fludakit.data.metamodel.ListAttribute;
import io.github.fludakit.data.metamodel.ListAttributeRecord;
import io.github.fludakit.data.metamodel.SortableColumnAttributeRecord;
import io.github.fludakit.data.metamodel.TextColumnAttributeRecord;
import jakarta.data.Sort;
import jakarta.data.metamodel.Attribute;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Locks the subtype-based {@link EntityDescriptor} contract: flat leaf resolution, column view, navigation refs. */
class EntityDescriptorTest {

    static class Person { }

    static class Friend { }

    static class Address { }

    /**
     * The generated shape: attributes() holds the ROOT members once (embedded and relation
     * containers included, flattened leaves NOT), columns() is the explicit flattened view and
     * navigationRefs() the literal map of relation-traversing paths.
     */
    record Fixture<T>(Class<T> entityClass, String tableName, IdAttribute<T> id,
                      List<Attribute<T>> attributes, List<ColumnRef> columns,
                      Map<String, ColumnRef> navigationRefs) implements EntityDescriptor<T> { }

    static EntityDescriptor<Address> address() {
        return new Fixture<>(Address.class, null, null,
                List.of(
                        new TextColumnAttributeRecord<Address>("street"),
                        new TextColumnAttributeRecord<Address>("city")),
                List.of(new ColumnRef("street", "street"), new ColumnRef("city", "city")),
                Map.of());
    }

    /** Person mirrors the generated shape: containers once, leaves only in the column view. */
    static EntityDescriptor<Person> person(EntityDescriptor<Address> embeddedAddress) {
        return new Fixture<>(Person.class, "person",
                new IdAttributeRecord<>("id"),
                List.of(
                        new SortableColumnAttributeRecord<Person>("name"),
                        new EmbeddableAttributeRecord<Person, Address>("address"),
                        new ListAttributeRecord<Person, Friend>("friends"),
                        new ListAttributeRecord<Person, String>("nicknames")),
                List.of(new ColumnRef("id", "id"),
                        new ColumnRef("name", "name"),
                        new ColumnRef("address_street", "address.street"),
                        new ColumnRef("address_zip", "address.zip")),
                Map.of());
    }

    @Test
    void attributeResolvesIdentifierFirstThenByName() {
        EntityDescriptor<Person> descriptor = person(address());

        assertThat(descriptor.attribute("id")).isEqualTo(new IdAttributeRecord<Person>("id"));
        assertThat(descriptor.attribute("name").name()).isEqualTo("name");
        assertThat(descriptor.attribute("address")).isInstanceOf(EmbeddableAttribute.class);
    }

    @Test
    void flattenedEmbeddedLeafResolvesThroughExplicitColumns() {
        // Post-Task-6 the leaf is no longer a flat attribute; the generated columns() view answers it.
        EntityDescriptor<Person> descriptor = person(address());

        assertThatThrownBy(() -> descriptor.attribute("address.street"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("address.street");
        assertThat(descriptor.columnRef("address.street"))
                .isEqualTo(new ColumnRef("address_street", "address.street"));
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
    void oneToManyAndElementCollectionContributeNoColumn() {
        List<ColumnRef> columns = person(address()).columns();

        assertThat(columns).extracting(ColumnRef::column)
                .doesNotContain("friends", "nicknames", "friend_id", "nickname_id");
    }

    @Test
    void relationAttributesExposeTheNavigableKindOnly() {
        // Slim attributes carry NAME and nothing else: classification is the interface type itself.
        EntityDescriptor<Person> descriptor = person(address());

        assertThat(descriptor.attribute("friends")).isInstanceOf(ListAttribute.class);
        assertThat(descriptor.attribute("nicknames")).isInstanceOf(ListAttribute.class);
        assertThat(descriptor.attribute("address")).isInstanceOf(EmbeddableAttribute.class);
    }

    @Test
    void sortableAttributesEmitJakartaSortsKeyedByAttributeName() {
        EntityDescriptor<Person> descriptor = person(address());

        Sort<Person> byName = ((io.github.fludakit.data.metamodel.SortableColumnAttribute<Person>)
                descriptor.attribute("name")).asc();
        assertThat(byName.property()).isEqualTo("name");
    }

    static class Parent { }

    static class Child { }

    static class Note { }

    static EntityDescriptor<Child> childMetadata() {
        return new Fixture<>(Child.class, "rel.child",
                new IdAttributeRecord<>("id"),
                List.of(new TextColumnAttributeRecord<Child>("label")),
                List.of(new ColumnRef("id", "id", "rel.child", List.of()),
                        new ColumnRef("label", "label", "rel.child", List.of()),
                        new ColumnRef("seq", "seq", "rel.child", List.of())),
                Map.of());
    }

    static EntityDescriptor<Note> noteMetadata() {
        return new Fixture<>(Note.class, null, null, List.of(),
                List.of(new ColumnRef("text", "text")), Map.of());
    }

    static EntityDescriptor<Parent> parentMetadata(EntityDescriptor<Child> child,
                                                   EntityDescriptor<Note> note) {
        return new Fixture<>(Parent.class, "rel.parent",
                new IdAttributeRecord<>("id"),
                List.of(
                        new TextColumnAttributeRecord<Parent>("name"),
                        new TextColumnAttributeRecord<Parent>("address.city"),
                        new EntityAttributeRecord<Parent, Child>("primary"),
                        new ListAttributeRecord<Parent, Child>("children"),
                        new ListAttributeRecord<Parent, Note>("notes")),
                List.of(new ColumnRef("id", "id"),
                        new ColumnRef("name", "name"),
                        new ColumnRef("address_city", "address.city"),
                        new ColumnRef("primary_id", "primary")),
                // The generated navigationRefs() literal: EXACTLY the paths the value() switch enumerates.
                Map.of(
                        "primary.label", new ColumnRef("label", "primary.label", "rel.child",
                                List.of(new JoinRef("rel.child", "primary", "primary_id", "id"))),
                        "children.seq", new ColumnRef("seq", "children.seq", "rel.child",
                                List.of(new JoinRef("rel.child", "children", "id", "owner_id"))),
                        "notes.text", new ColumnRef("text", "notes.text", null,
                                List.of(new JoinRef("parent_notes", "notes", "id", "parent_id")))));
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
    void navigationRefsDefaultIsEmptyAndNavigatedPathFailsFast() {
        // A hand-rolled descriptor without a navigationRefs override navigates only its columns view.
        EntityDescriptor<Person> descriptor = person(address());

        assertThat(descriptor.navigationRefs()).isEmpty();
        assertThatThrownBy(() -> descriptor.columnRef("friends.name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown property 'friends.name' on Person");
    }

    @Test
    void columnRefRejectsUnknownHeadAndNonNavigableHead() {
        EntityDescriptor<Parent> descriptor = parentMetadata(childMetadata(), noteMetadata());

        assertThatThrownBy(() -> descriptor.columnRef("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown property 'nope'");
        assertThatThrownBy(() -> descriptor.columnRef("name.x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown property 'name.x' on Parent");
        assertThatThrownBy(() -> descriptor.columnRef("primary.nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown property 'primary.nope' on Parent");
    }
}
