package io.github.fludakit.data.it;

import io.github.fludakit.data.annotation.AggregateRoot;
import io.github.fludakit.data.annotation.Identity;

@AggregateRoot
public class Profile {
    @Identity Long id;
    Address address;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Address getAddress() { return address; }
    public void setAddress(Address address) { this.address = address; }
}
