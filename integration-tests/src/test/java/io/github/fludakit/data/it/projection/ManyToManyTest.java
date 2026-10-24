package io.github.fludakit.data.it.projection;

import io.github.fludakit.data.it.DataSourceProducer;

import io.github.fludakit.jdbc.JdbcClient;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips a many-to-many relationship between two aggregate roots through the join table.
 *
 * <p>{@code Book.authors} and {@code Author.books} are mutual entity collections, so the type
 * analysis attaches one canonical {@link io.github.fludakit.data.metadata.JoinTable} to both sides.
 * The table is named alphabetically ({@code author_book}) with {@code author_id} naming the
 * alphabetically-first side and {@code book_id} the other. Reading a {@code Book} hydrates its
 * {@code authors} through {@code author_book}; saving a {@code Book} syncs the join-table rows only
 * (the authors themselves are aggregate roots managed by their own repository), and deleting a
 * {@code Book} removes the link rows only — never the author rows.</p>
 */
@EnableAutoWeld
@AddPackages(Book.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class ManyToManyTest {

    @Inject AuthorRepository authors;
    @Inject BookRepository books;
    @Inject JdbcClient jdbcClient;

    @Test
    void saveSyncsLinkRowsAndHydratesThemBack() {
        Author ursula = authors.save(author("Ursula"));
        Author ted = authors.save(author("Ted"));

        Book book = new Book();
        book.setTitle("A Wizard");
        book.setAuthors(List.of(ursula, ted));
        Book saved = books.save(book);

        assertThat(linkCount(saved.getId())).isEqualTo(2);

        Book back = books.findById(saved.getId()).orElseThrow();
        assertThat(back.getAuthors()).extracting(Author::getName)
                .containsExactlyInAnyOrder("Ursula", "Ted");
    }

    @Test
    void reSaveDoesNotDuplicateAndRemovesOrphanLinks() {
        Author ursula = authors.save(author("Ursula"));
        Author ted = authors.save(author("Ted"));

        Book book = new Book();
        book.setTitle("Lathe");
        book.setAuthors(List.of(ursula, ted));
        Book saved = books.save(book);
        assertThat(linkCount(saved.getId())).isEqualTo(2);

        // Re-saving the same links must not insert duplicate rows; dropping Ted must delete his link.
        book.setAuthors(List.of(ursula));
        books.save(book);

        assertThat(linkCount(saved.getId())).isEqualTo(1);

        Book back = books.findById(saved.getId()).orElseThrow();
        assertThat(back.getAuthors()).extracting(Author::getName).containsExactly("Ursula");
    }

    @Test
    void deleteRemovesOnlyLinkRowsNotAuthors() {
        Author ursula = authors.save(author("Ursula"));

        Book book = new Book();
        book.setTitle("The Left Hand");
        book.setAuthors(List.of(ursula));
        Book saved = books.save(book);
        assertThat(linkCount(saved.getId())).isEqualTo(1);

        books.deleteById(saved.getId());

        assertThat(linkCount(saved.getId())).isZero();
        // The author is an aggregate root managed by its own repository: the link deletion must not
        // cascade into the authors table.
        assertThat(authors.findById(ursula.getId())).isPresent();
    }

    private Long linkCount(Long bookId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM author_book WHERE book_id = :bookId")
                .param("bookId", bookId)
                .singleValue(Long.class);
    }

    private static Author author(String name) {
        Author a = new Author();
        a.setName(name);
        return a;
    }
}
