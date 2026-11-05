package io.github.fludakit.data.it.underscore;

import io.github.fludakit.data.annotation.AggregateRoot;
import io.github.fludakit.data.annotation.Identity;

/**
 * An aggregate whose embedded property is named {@code home_address}. The underscored name is the
 * point of this fixture: the flattened columns are {@code home_address_*}, and the generated write
 * code must reach them through {@code getHome_address()}, not through a re-split
 * {@code getHome().getAddress()}.
 */
@AggregateRoot
public class Site {
    @Identity Long id;
    String name;
    Location home_address;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Location getHome_address() { return home_address; }
    public void setHome_address(Location homeAddress) { this.home_address = homeAddress; }
}
