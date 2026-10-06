package io.github.fludakit.data.it.estore;

import io.github.fludakit.data.annotation.AggregateRoot;
import io.github.fludakit.data.annotation.Association;
import io.github.fludakit.data.annotation.Identity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@AggregateRoot
public class Order {
    @Identity Long id;
    BigDecimal amount;
    @Association Customer customer;
    List<OrderItem> orderItems = new ArrayList<>();

    public Order() {}
    public Order(Customer customer) { this.customer = customer; }
    public void addItem(Product product, int quantity) { orderItems.add(new OrderItem(product, quantity)); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Customer getCustomer() { return customer; }
    public void setCustomer(Customer customer) { this.customer = customer; }
    public List<OrderItem> getOrderItems() { return orderItems; }
    public void setOrderItems(List<OrderItem> orderItems) { this.orderItems = orderItems; }
}
