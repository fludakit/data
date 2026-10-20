package io.github.fludakit.data.it.projection;

import io.github.fludakit.data.annotation.AggregateRoot;
import io.github.fludakit.data.annotation.Identity;

/**
 * The MANAGED definition of the composing to-one projection corpus: its own {@code @Repository} is what
 * makes {@link Book#author} a composing {@code Reference} rather than an unresolved field, and its
 * {@code name} leaf is what the JOIN-projected read must bring back FULL (an id-only partial would
 * leave it null).
 */
@AggregateRoot
public class Author {
    @Identity Long id;
    String name;

    public Author() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
