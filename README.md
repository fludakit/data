# FluDa Data

[![Build](https://github.com/fludakit/data/actions/workflows/build.yml/badge.svg)](https://github.com/fludakit/data/actions/workflows/build.yml)

A fluent data-access toolkit for Jakarta EE / CDI applications, providing compile-time repository generation over JDBC. It implements a subset of Jakarta Data 1.0 and embraces Domain-Driven Design modeling concepts through its own entity model annotations.

## Usage

```java
import io.github.fludakit.data.annotation.Entity;
import io.github.fludakit.data.annotation.Identity;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

@Entity
public class Customer {
    @Identity Long id;
    String name;
    String email;
    // getters and setters
}

@Repository
public interface CustomerRepository extends CrudRepository<Customer, Long> {
    List<Customer> findByName(String name);
    long countByEmail(String email);
}
```

The processor generates a CDI bean `CustomerRepository_` with static SQL constants, a code-generated row mapper, and a Jakarta Data static metamodel. Add `fluda-data-qbn` to the annotation processor path to enable query-by-method-name finders like `findByName` and `countByEmail`.

## Building

```bash
./mvnw clean install
```

## Documentation

- [Product specification](docs/PRODUCT-SPEC.md) — the v1 Jakarta Data scope and DDD entity model.
- [Technical specification](docs/TECH-SPEC.md) — processor architecture and refactoring roadmap.
- [Reference documentation](https://fludakit.github.io/documentation/data/getting-started/) — getting started, repository patterns, query-by-method-name, and full API reference.
- [CONTRIBUTING.md](CONTRIBUTING.md) — how to build the project and submit changes.
