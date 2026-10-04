package io.github.fludakit.data.it;

import io.github.fludakit.data.annotation.AggregateRoot;
import io.github.fludakit.data.annotation.Identity;

@AggregateRoot
public class Customer {
    @Identity Long id;
    String name;
    String email;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
