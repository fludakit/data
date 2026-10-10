package io.github.fludakit.data.it.task;

import io.github.fludakit.data.annotation.Entity;
import io.github.fludakit.data.annotation.Identity;

@Entity
public class Task {
    @Identity Long id;
    String title;
    Status status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
}
