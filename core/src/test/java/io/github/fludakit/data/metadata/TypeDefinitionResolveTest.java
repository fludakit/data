package io.github.fludakit.data.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TypeDefinitionResolveTest {

    private static final TypeDefinition.Aggregate AUTHOR = author();
    private static final TypeDefinition.Aggregate BOOK = book();

    @Test
    void manyToManyPathResolvesThroughTwoEdges() {
        ColumnPlacement placement = BOOK.resolve("authors.name");

        assertThat(placement.leaf()).isEqualTo(new ColumnRef.Simple("name", "authors"));
        assertThat(placement.edges()).hasSize(2);

        // First edge: root id → join table's book_id (a ToMany: the join-table FK names the root).
        JoinEdge first = placement.edges().get(0);
        assertThat(first).isInstanceOf(JoinEdge.ToMany.class);
        assertThat(first.ownerTable()).isEqualTo("books");
        assertThat(first.ownerColumn()).isEqualTo("id");
        assertThat(first.childTable()).isEqualTo("author_book");
        assertThat(first.childColumn()).isEqualTo("book_id");

        // Second edge: join table's author_id → authors id (a ToOne closing onto the target).
        JoinEdge second = placement.edges().get(1);
        assertThat(second).isInstanceOf(JoinEdge.ToOne.class);
        assertThat(second.ownerTable()).isEqualTo("author_book");
        assertThat(second.ownerColumn()).isEqualTo("author_id");
        assertThat(second.childTable()).isEqualTo("authors");
        assertThat(second.childColumn()).isEqualTo("id");
    }

    @Test
    void manyToManyColumnForRejectsUnknownSide() {
        JoinTable joinTable = new JoinTable("author_book", "author_id", "book_id",
                "Author", "Book");
        assertThat(joinTable.columnFor("Author")).isEqualTo("author_id");
        assertThat(joinTable.columnFor("Book")).isEqualTo("book_id");
        assertThrows(IllegalArgumentException.class, () -> joinTable.columnFor("Publisher"));
    }

    private static TypeDefinition.Aggregate author() {
        TypeDefinition.Prop.Id id = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id",
                TypeDefinition.Prop.Id.GeneratedType.IDENTITY);
        return new TypeDefinition.Aggregate(
                "Author", "authors", id, null,
                List.of(new TypeDefinition.Prop.Scalar("name", "java.lang.String", "name", false)),
                List.of(), List.of(), List.of(), List.of());
    }

    private static TypeDefinition.Aggregate book() {
        TypeDefinition.Prop.Id id = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id",
                TypeDefinition.Prop.Id.GeneratedType.IDENTITY);
        JoinTable joinTable = new JoinTable("author_book", "author_id", "book_id",
                "Author", "Book");
        TypeDefinition.Prop.EntityCollection authors = new TypeDefinition.Prop.EntityCollection(
                "authors", "java.util.List", AUTHOR, null, null, joinTable);
        return new TypeDefinition.Aggregate(
                "Book", "books", id, null,
                List.of(new TypeDefinition.Prop.Scalar("title", "java.lang.String", "title", false)),
                List.of(), List.of(), List.of(authors), List.of());
    }
}
