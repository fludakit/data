package io.github.fludakit.data.it.projection;

import io.github.fludakit.data.it.DataSourceProducer;

import jakarta.data.Order;
import jakarta.data.page.Page;
import jakarta.data.page.PageRequest;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Read-side JOIN projection of a COMPOSING to-one reference, end-to-end — see
 * {@code docs/JOIN-PROJECTION.md}.
 *
 * <p>{@link Book#author} is a bare entity field on an {@code @AggregateRoot} whose definition is managed
 * (it has its own {@code @Repository}), so every CRUD read JOIN-projects it: the generated constant
 * carries {@code LEFT JOIN "authors" AS "authors"} plus {@code "authors"."name" AS "authors_join_name"},
 * and a generated {@code mapRow_full_crud} materialises the definition from those labels. The definition
 * therefore comes back FULL — not the id-only partial an {@code @Association} would produce.</p>
 *
 * <p>Contract locks:</p>
 * <ul>
 *   <li>a to-one join adds at most one row per parent, so LIMIT/COUNT still count ROOTS;</li>
 *   <li>the join is a LEFT JOIN and the projection is guarded on the definition id, so a root with no
 *       definition still comes back with a null reference;</li>
 *   <li>the paged read keeps its runtime sort/join splice, which must not duplicate a join the base
 *       constant already carries.</li>
 * </ul>
 */
@EnableAutoWeld
@AddPackages(Book.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class ComposingToOneProjectionTest {

    @Inject AuthorRepository authors;
    @Inject BookRepository books;

    @Test
    void findByIdMaterialisesTheComposingTargetFully() {
        Author ursula = authors.save(author("Ursula"));
        Book saved = books.save(book("The Left Hand", ursula));

        Book back = books.findById(saved.getId()).orElseThrow();
        assertThat(back.getTitle()).isEqualTo("The Left Hand");
        assertThat(back.getAuthor()).isNotNull();
        assertThat(back.getAuthor().getId()).isEqualTo(ursula.getId());
        assertThat(back.getAuthor().getName())
                .as("a composing to-one read is JOIN-projected, so the definition is fully materialised")
                .isEqualTo("Ursula");
    }

    @Test
    void findAllMaterialisesTheComposingTargetFully() {
        Author ursula = authors.save(author("Ursula"));
        Author ted = authors.save(author("Ted"));
        books.save(book("A Wizard", ursula));
        books.save(book("Lathe", ted));

        List<Book> all = books.findAll().toList();
        assertThat(all).hasSize(2);
        assertThat(all).allSatisfy(b -> {
            assertThat(b.getAuthor()).isNotNull();
            assertThat(b.getAuthor().getName()).isEqualTo(b.getTitle().equals("A Wizard") ? "Ursula" : "Ted");
        });
    }

    @Test
    void rootWithoutATargetComesBackWithANullReference() {
        // LEFT JOIN + the joined mapper's null-id guard: a root whose fk is NULL must still be read,
        // and its reference must stay null rather than materialise a zero/blank definition.
        Book saved = books.save(book("Anonymous", null));

        Book back = books.findById(saved.getId()).orElseThrow();
        assertThat(back.getTitle()).isEqualTo("Anonymous");
        assertThat(back.getAuthor()).isNull();
    }

    @Test
    void pagedReadCountsRootsAndMaterialisesTargets() {
        Author ursula = authors.save(author("Ursula"));
        books.save(book("Alpha", ursula));
        books.save(book("Beta", ursula));

        Page<Book> page = books.findAll(PageRequest.ofPage(1).size(10), Order.by(_Book.title.asc()));
        // A to-one join cannot fan out, so the root count is the page size, not the join-row count.
        assertThat(page.content()).hasSize(2);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).extracting(Book::getTitle).containsExactly("Alpha", "Beta");
        assertThat(page.content()).allSatisfy(b -> assertThat(b.getAuthor().getName()).isEqualTo("Ursula"));
    }

    @Test
    void pagedReadSortedThroughTheProjectedJoinDoesNotDuplicateIt() {
        // The base constant already carries LEFT JOIN "authors". A runtime sort on author.name resolves
        // to an edge onto the SAME table; splicing it again would repeat the alias and fail at parse
        // time, so the emission must suppress an edge the base already joins.
        Author ursula = authors.save(author("Ursula"));
        Author ted = authors.save(author("Ted"));
        books.save(book("Alpha", ursula));
        books.save(book("Beta", ted));

        Page<Book> page = books.findAll(PageRequest.ofPage(1).size(10), Order.by(_Book.author.get(_Author.name).asc()));
        assertThat(page.content()).extracting(Book::getTitle).containsExactly("Beta", "Alpha");
        assertThat(page.content()).allSatisfy(b -> assertThat(b.getAuthor().getName()).isNotNull());
    }

    private static Author author(String name) {
        Author a = new Author();
        a.setName(name);
        return a;
    }

    private static Book book(String title, Author author) {
        Book b = new Book();
        b.setTitle(title);
        b.setAuthor(author);
        return b;
    }
}
