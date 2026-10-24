package io.github.fludakit.data.it.cascade;

import io.github.fludakit.data.it.DataSourceProducer;
import io.github.fludakit.data.it.projection.*;

import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests Rule 4 (Aggregate nesting) and composing to-one cascade behavior.
 * 
 * Rule 4 states that entity collections whose target is @AggregateRoot are skipped (no cascade).
 * However, composing to-one associations to @AggregateRoot targets ARE cascaded on save/update.
 * 
 * Note: Cascade delete for composing to-one associations is not currently implemented
 * (only entity collections and element collections are cascade-deleted).
 */
@EnableAutoWeld
@AddPackages(Book.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class AggregateNestingTest {

    @Inject AuthorRepository authors;
    @Inject BookRepository books;

    @Test
    void compositionToOneToAggregateTargetIsCascadedOnSave() {
        // Book is @AggregateRoot, Author is @AggregateRoot
        // Book->Author is composition to-one (composition=true)
        // This SHOULD cascade: saving Book saves Author
        Author author = new Author();
        author.setName("Test Author");

        Book book = new Book();
        book.setTitle("Test Book");
        book.setAuthor(author);

        Book saved = books.save(book);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getAuthor()).isNotNull();
        assertThat(saved.getAuthor().getId()).isNotNull();
        assertThat(authors.findById(saved.getAuthor().getId())).isPresent();
    }

    @Test
    void compositionToOneToAggregateTargetIsCascadedOnUpdate() {
        // Verify that updating the root also updates the composing to-one target
        Author author = new Author();
        author.setName("Original Name");

        Book book = new Book();
        book.setTitle("Test Book");
        book.setAuthor(author);

        Book saved = books.save(book);
        Long authorId = saved.getAuthor().getId();

        // Update the author name through the book
        saved.getAuthor().setName("Updated Name");
        books.save(saved);

        // Verify the author was updated
        Author reloaded = authors.findById(authorId).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Updated Name");
    }
}
