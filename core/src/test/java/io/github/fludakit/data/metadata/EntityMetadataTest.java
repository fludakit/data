package io.github.fludakit.data.metadata;

import io.github.fludakit.data.metamodel.EmbeddableAttribute;
import io.github.fludakit.data.metamodel.EmbeddableAttributeRecord;
import io.github.fludakit.data.metamodel.EntityAttributeRecord;
import io.github.fludakit.data.metamodel.IdAttribute;
import io.github.fludakit.data.metamodel.IdAttributeRecord;
import io.github.fludakit.data.metamodel.ListAttribute;
import io.github.fludakit.data.metamodel.ListAttributeRecord;
import io.github.fludakit.data.metamodel.PathAttribute;
import io.github.fludakit.data.metamodel.SortableScalarAttributeRecord;
import io.github.fludakit.data.metamodel.TextScalarAttributeRecord;
import jakarta.data.Sort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Locks the subtype-based {@link EntityMetadata} contract: flat leaf resolution, column view, navigation refs. */
class EntityMetadataTest {

    static class Person { }

    static class Friend { }

    static class Address { }

    /**
     * The generated shape: attributes() holds the ROOT members once (embedded and relation
     * containers included, flattened leaves NOT), while columnRefs() is the entity-rooted typed
     * index and columns() derives the root table's local view from that index.
     */
    record Fixture<T>(Class<T> entityClass, String tableName, IdAttribute<T> id,
                      List<PathAttribute<T>> attributes, List<ColumnRef> columns,
                      Map<String, ColumnRef> columnRefs) implements EntityMetadata<T> {
        @Override
        public Stereotype stereotype() {
            return tableName == null ? Stereotype.VALUE_OBJECT : Stereotype.ENTITY;
        }
    }

    static EntityMetadata<Address> address() {
        return new Fixture<>(Address.class, null, null,
                List.of(
                        new TextScalarAttributeRecord<Address>("street"),
                        new TextScalarAttributeRecord<Address>("city")),
                List.of(new ColumnRef("street"), new ColumnRef("city")),
                Map.of());
    }

    /** Person mirrors the generated shape: containers once, leaves only in the column view. */
    static EntityMetadata<Person> person(EntityMetadata<Address> embeddedAddress) {
        return new Fixture<>(Person.class, "person",
                new IdAttributeRecord<>("id"),
                List.of(
                        new SortableScalarAttributeRecord<Person>("name"),
                        new EmbeddableAttributeRecord<Person, Address>("address"),
                        new ListAttributeRecord<Person, Friend>("friends"),
                        new ListAttributeRecord<Person, String>("nicknames")),
                List.of(new ColumnRef("id", "person"),
                        new ColumnRef("name", "person"),
                        new ColumnRef("address_street", "person"),
                        new ColumnRef("address_zip", "person")),
                Map.of(
                        "id", new ColumnRef("id", "person"),
                        "name", new ColumnRef("name", "person"),
                        "address.street", new ColumnRef("address_street", "person"),
                        "address.zip", new ColumnRef("address_zip", "person")));
    }

    @Test
    void attributeResolvesIdentifierFirstThenByName() {
        EntityMetadata<Person> descriptor = person(address());

        assertThat(descriptor.attribute("id")).isEqualTo(new IdAttributeRecord<Person>("id"));
        assertThat(descriptor.attribute("name").name()).isEqualTo("name");
        assertThat(descriptor.attribute("address")).isInstanceOf(EmbeddableAttribute.class);
    }

    @Test
    void flattenedEmbeddedLeafResolvesThroughExplicitColumns() {
        // Post-Task-6 the leaf is no longer a flat attribute; the generated columns() view answers it.
        EntityMetadata<Person> descriptor = person(address());

        assertThatThrownBy(() -> descriptor.attribute("address.street"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("address.street");
        assertThat(descriptor.columnRef("address.street"))
                .isEqualTo(new ColumnRef("address_street", "person"));
    }

    @Test
    void attributeThrowsForUnknownPropertyNamingPropertyAndType() {
        EntityMetadata<Person> descriptor = person(address());

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
                new ColumnRef("id", "person"),
                new ColumnRef("name", "person"),
                new ColumnRef("address_street", "person"),
                new ColumnRef("address_zip", "person"));
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
        EntityMetadata<Person> descriptor = person(address());

        assertThat(descriptor.attribute("friends")).isInstanceOf(ListAttribute.class);
        assertThat(descriptor.attribute("nicknames")).isInstanceOf(ListAttribute.class);
        assertThat(descriptor.attribute("address")).isInstanceOf(EmbeddableAttribute.class);
    }

    @Test
    void sortableAttributesEmitJakartaSortsKeyedByAttributeName() {
        EntityMetadata<Person> descriptor = person(address());

        Sort<Person> byName = ((io.github.fludakit.data.metamodel.SortableScalarAttribute<Person>)
                descriptor.attribute("name")).asc();
        assertThat(byName.property()).isEqualTo("name");
    }

    @Test
    void attributesExposeLogicalNamesAndRootRelativePaths() {
        var address = new EmbeddableAttributeRecord<Person, Address>("address", "address");
        var zipCode = new io.github.fludakit.data.metamodel.SortableScalarAttributeRecord<Person>(
                "zipCode", "address.zipCode");

        assertThat(address.name()).isEqualTo("address");
        assertThat(address.path()).isEqualTo("address");
        assertThat(zipCode.name()).isEqualTo("zipCode");
        assertThat(zipCode.path()).isEqualTo("address.zipCode");
        assertThat(zipCode.asc().property()).isEqualTo("address.zipCode");
    }

    static class Parent { }

    static class Child { }

    static class Note { }

    static EntityMetadata<Child> childMetadata() {
        return new Fixture<>(Child.class, "rel.child",
                new IdAttributeRecord<>("id"),
                List.of(new TextScalarAttributeRecord<Child>("label")),
                List.of(new ColumnRef("id", "rel.child"),
                        new ColumnRef("label", "rel.child"),
                        new ColumnRef("seq", "rel.child")),
                Map.of());
    }

    static EntityMetadata<Note> noteMetadata() {
        return new Fixture<>(Note.class, null, null, List.of(),
                List.of(new ColumnRef("text")), Map.of());
    }

    static EntityMetadata<Parent> parentMetadata(EntityMetadata<Child> child,
                                                   EntityMetadata<Note> note) {
        return new Fixture<>(Parent.class, "rel.parent",
                new IdAttributeRecord<>("id"),
                List.of(
                        new TextScalarAttributeRecord<Parent>("name"),
                        new EmbeddableAttributeRecord<Parent, Address>("address", "address"),
                        new EntityAttributeRecord<Parent, Child>("primary"),
                        new ListAttributeRecord<Parent, Child>("children"),
                        new ListAttributeRecord<Parent, Note>("notes")),
                List.of(new ColumnRef("id", "rel.parent"),
                        new ColumnRef("name", "rel.parent"),
                        new ColumnRef("address_city", "rel.parent"),
                        new ColumnRef("primary_id", "rel.parent")),
                // The typed index covers local and relation-traversing paths as generated metadata does.
                Map.of(
                        "id", new ColumnRef("id", "rel.parent"),
                        "name", new ColumnRef("name", "rel.parent"),
                        "address.city", new ColumnRef("address_city", "rel.parent"),
                        "primary", new ColumnRef("primary_id", "rel.parent"),
                        "primary.label", new ColumnRef("label", "rel.child", "primary",
                                List.of(new JoinRef("rel.parent", null, "primary_id",
                                        "rel.child", "primary", "id"))),
                        "children.seq", new ColumnRef("seq", "rel.child", "children",
                                List.of(new JoinRef("rel.parent", null, "id",
                                        "rel.child", "children", "owner_id"))),
                        "notes.text", new ColumnRef("text", "parent_notes", "notes",
                                List.of(new JoinRef("rel.parent", null, "id",
                                        "parent_notes", "notes", "parent_id")))));
    }

    @Test
    void columnRefResolvesLocalAndFlattenedEmbeddedPathsAsPlainColumns() {
        EntityMetadata<Parent> descriptor = parentMetadata(childMetadata(), noteMetadata());

        ColumnRef local = descriptor.columnRef("name");
        assertThat(local.column()).isEqualTo("name");
        assertThat(local.ownerTableName()).isEqualTo("rel.parent");
        assertThat(local.joins()).isEmpty();

        ColumnRef embedded = descriptor.columnRef("address.city");
        assertThat(embedded.column()).isEqualTo("address_city");
    }

    @Test
    void columnRefThroughForeignKeyCarriesTargetTableAndJoin() {
        ColumnRef viaFk = parentMetadata(childMetadata(), noteMetadata()).columnRef("primary.label");

        assertThat(viaFk.column()).isEqualTo("label");
        assertThat(viaFk.ownerTableName()).isEqualTo("rel.child");
        assertThat(viaFk.ownerAlias()).isEqualTo("primary");
        assertThat(viaFk.joins())
                .containsExactly(new JoinRef("rel.parent", null, "primary_id",
                        "rel.child", "primary", "id"));
    }

    @Test
    void columnRefThroughOneToManyJoinsOnDerivedChildFkColumnAndKeepsFullPath() {
        ColumnRef viaOtm = parentMetadata(childMetadata(), noteMetadata()).columnRef("children.seq");

        assertThat(viaOtm.joins())
                .containsExactly(new JoinRef("rel.parent", null, "id",
                        "rel.child", "children", "owner_id"));
        assertThat(viaOtm.column()).isEqualTo("seq");
    }

    @Test
    void columnRefThroughElementCollectionJoinsChildTableOnParentFkColumn() {
        ColumnRef viaEc = parentMetadata(childMetadata(), noteMetadata()).columnRef("notes.text");

        assertThat(viaEc.joins())
                .containsExactly(new JoinRef("rel.parent", null, "id",
                        "parent_notes", "notes", "parent_id"));
    }

    @Test
    void columnRefsDefaultIsEmptyAndNavigatedPathFailsFast() {
        // A hand-written metadata implementation without an columnRefs override resolves columns only.
        EntityMetadata<Person> descriptor = person(address());

        assertThat(descriptor.columnRefs()).hasSize(4);
        assertThatThrownBy(() -> descriptor.columnRef("friends.name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown property 'friends.name' on Person");
    }

    @Test
    void columnRefRejectsUnknownHeadAndNonNavigableHead() {
        EntityMetadata<Parent> descriptor = parentMetadata(childMetadata(), noteMetadata());

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
