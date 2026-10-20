package io.github.fludakit.data.it.projection;

import io.github.fludakit.data.it.DataSourceProducer;

import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Write-side counterpart of the composing to-one projection: the aggregate root {@link Book} OWNS its
 * {@link Book#author}, so a write of the root writes the definition too — before the root's own row,
 * because that row carries the definition's foreign key and binds it from the live object.
 *
 * <p>The definition is written by the same rules the root is: inserted with a key holder when it has no
 * identifier yet (so the root's fk bind sees the generated key), and upserted when it has one. An
 * {@code @Association} — composition {@code false} — is an external entity the root merely points at
 * and is never written.</p>
 */
@EnableAutoWeld
@AddPackages(Book.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class ComposingToOneWriteTest {

    @Inject AuthorRepository authors;
    @Inject BookRepository books;

    @Test
    void saveInsertsAnOwnedTargetThatHasNoIdYet() {
        // The root's fk is bound from the definition object, so a definition with no id must be written FIRST
        // and its generated key backfilled onto the object the root then reads.
        Author ursula = author("Ursula");
        Book saved = books.save(book("The Left Hand", ursula));

        assertThat(ursula.getId())
                .as("the generated definition id must be backfilled onto the object the root binds")
                .isNotNull();

        Book back = books.findById(saved.getId()).orElseThrow();
        assertThat(back.getAuthor()).isNotNull();
        assertThat(back.getAuthor().getId()).isEqualTo(ursula.getId());
        assertThat(back.getAuthor().getName()).isEqualTo("Ursula");
        // The row is real, not just a foreign key: the definition's own repository finds it.
        assertThat(authors.findById(ursula.getId()).orElseThrow().getName()).isEqualTo("Ursula");
    }

    @Test
    void saveUpsertsAnOwnedTargetInPlace() {
        // A definition that already carries an id is updated under its own id — never inserted again, so
        // saving the root twice produces one author row, not two.
        Author ursula = authors.save(author("Ursula"));
        Long authorId = ursula.getId();
        Book saved = books.save(book("A Wizard", ursula));

        ursula.setName("Ursula K. Le Guin");
        books.save(saved);

        assertThat(authors.findById(authorId).orElseThrow().getName()).isEqualTo("Ursula K. Le Guin");
        assertThat(books.findById(saved.getId()).orElseThrow().getAuthor().getName())
                .isEqualTo("Ursula K. Le Guin");
    }

    @Test
    void updateWritesAnOwnedTargetToo() {
        // update() is not a root-only statement: the composition lifecycle is bound to the root.
        Author ursula = authors.save(author("Ursula"));
        Book saved = books.save(book("A Wizard", ursula));

        ursula.setName("Ursula K. Le Guin");
        saved.setTitle("A Wizard of Earthsea");
        books.update(saved);

        Book back = books.findById(saved.getId()).orElseThrow();
        assertThat(back.getTitle()).isEqualTo("A Wizard of Earthsea");
        assertThat(back.getAuthor().getName()).isEqualTo("Ursula K. Le Guin");
        assertThat(authors.findById(ursula.getId()).orElseThrow().getName())
                .isEqualTo("Ursula K. Le Guin");
    }

    @Test
    void aNullTargetIsSkippedNotWritten() {
        // A root with no definition must not fabricate one.
        Book saved = books.save(book("Anonymous", null));

        Book back = books.findById(saved.getId()).orElseThrow();
        assertThat(back.getAuthor()).isNull();
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
