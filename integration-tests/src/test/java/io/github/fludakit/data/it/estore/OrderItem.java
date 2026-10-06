package io.github.fludakit.data.it.estore;

import io.github.fludakit.data.annotation.Association;
import io.github.fludakit.data.annotation.ValueObject;

@ValueObject
public class OrderItem {
    @Association Product product;
    int quantity;

    public OrderItem() {}
    public OrderItem(Product product, int quantity) {
        this.product = product;
        this.quantity = quantity;
    }

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}
