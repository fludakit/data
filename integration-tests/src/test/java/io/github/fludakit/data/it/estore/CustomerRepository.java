package io.github.fludakit.data.it.estore;

import io.github.fludakit.jdbc.JdbcClient;
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

    /**
     * The escape hatch: declared like any other repository method, and answered by the generated
     * implementation with the {@code JdbcClient} it was injected with. It is NOT a query — the
     * processor recognises it on its signature and never asks the query compiler for it.
     * {@code CustomerRepositoryTest} runs an ad-hoc statement through it.
     */
    JdbcClient getJdbcClient();

    @Find
    List<Customer> findByName(@By("name") String name);

    @Find
    @OrderBy("name")
    List<Customer> findByEmail(@By("email") String email);

    @Find
    @OrderBy("id")
    CursoredPage<Customer> paged(PageRequest pageRequest);
}
