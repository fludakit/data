package io.github.fludakit.data.it.projection;

import io.github.fludakit.data.annotation.AggregateRoot;
import io.github.fludakit.data.annotation.Identity;

/**
 * One side of the many-to-many corpus: {@code authors} is an entity collection whose inverse is
 * {@link Author#books}, so the type analysis attaches a canonical join table ({@code author_book})
 * to both sides rather than storing a foreign key on either table.
 */
@AggregateRoot
public class Book {
    @Identity Long id;
    String title;
    java.util.List<Author> authors;

    public Book() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public java.util.List<Author> getAuthors() { return authors; }
    public void setAuthors(java.util.List<Author> authors) { this.authors = authors; }
}
