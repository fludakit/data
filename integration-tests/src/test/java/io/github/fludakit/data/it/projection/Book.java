package io.github.fludakit.data.it.projection;

import io.github.fludakit.data.annotation.AggregateRoot;
import io.github.fludakit.data.annotation.Identity;

/**
 * The owner of the composing to-one projection corpus: {@code author} is a bare entity field on an
 * {@code @AggregateRoot} whose target is managed, so it classifies as COMPOSING and every CRUD read
 * JOIN-projects it onto the book's own row instead of re-reading it in a second phase.
 *
 * <p>The fk column is {@code author_id} on {@code book}; the join is a plain LEFT JOIN, so a book
 * with no author still comes back (with a null reference).</p>
 */
@AggregateRoot
public class Book {
    @Identity Long id;
    String title;
    Author author;

    public Book() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Author getAuthor() { return author; }
    public void setAuthor(Author author) { this.author = author; }
}
