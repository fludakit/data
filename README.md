# FluDa Data

[![Build](https://github.com/fludakit/data/actions/workflows/build.yml/badge.svg)](https://github.com/fludakit/data/actions/workflows/build.yml)

A fluent data-access toolkit for Jakarta EE / CDI applications, providing compile-time repository generation over JDBC.

## Modules

- `fluda-data-core`: the core API, annotations, and supporting types.
- `fluda-data-qbn`: optional query-by-method-name parser for derived finders.
- `fluda-data-processor`: the annotation processor that generates repository implementations.

## Usage

Define a repository interface and let the processor generate the implementation:

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

The processor generates a CDI bean `CustomerRepositoryImpl` with static SQL constants, a reflection-free row mapper, and a Jakarta Data static metamodel. Add `fluda-data-qbn` to the annotation processor path to enable query-by-method-name finders like `findByName` and `countByEmail`.

For annotations, repository patterns, query-by-method-name conformance, relation traversal, paging, and aggregate composition, see the [Data documentation](https://fludakit.github.io/documentation/data/getting-started/).

## Building

```bash
./mvnw clean install
```

## Documentation

See the [reference documentation site](https://fludakit.github.io/documentation/data/getting-started/) for getting started, repository patterns, query-by-method-name, and full API reference.

## Contributing

Contributions are welcome — issues, pull requests, and feature suggestions are all encouraged. See [CONTRIBUTING.md](CONTRIBUTING.md) for how to build the project and submit changes.

