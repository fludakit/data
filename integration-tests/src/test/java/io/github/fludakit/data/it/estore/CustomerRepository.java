package io.github.fludakit.data.it.estore;

import jakarta.data.page.CursoredPage;
import jakarta.data.page.PageRequest;
import jakarta.data.repository.By;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Find;
import jakarta.data.repository.OrderBy;
import jakarta.data.repository.Repository;

import java.util.List;

@Repository
public interface CustomerRepository extends CrudRepository<Customer, Long> {
    @Find
    List<Customer> findByName(@By("name") String name);

    @Find
    @OrderBy("name")
    List<Customer> findByEmail(@By("email") String email);

    @Find
    @OrderBy("id")
    CursoredPage<Customer> paged(PageRequest pageRequest);
}
