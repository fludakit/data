package io.github.fludakit.data.it.estore;

import io.github.fludakit.data.annotation.Entity;
import io.github.fludakit.data.annotation.Identity;

import java.math.BigDecimal;

@Entity
public class Product {
    @Identity Long id;
    String name;
    BigDecimal price;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
}
