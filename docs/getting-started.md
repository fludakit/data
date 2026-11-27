# Getting Started

This guide covers adding FluDa Data to your project, mapping an entity, declaring a repository, and using the three ways to express persistence operations: the lifecycle annotations, the built-in repository methods, and query-by-method-name.

## Requirements

- JDK 21 or later.
- The Jakarta Data API (`jakarta.data:jakarta.data-api`).
- The FluDa [JDBC Client](https://fludakit.github.io/jdbc-client/getting-started/) — generated implementations execute SQL through `JdbcClient`. In a CDI environment add `fluda-jdbc-cdi` and provide a `DataSource`.
- A CDI-compatible runtime to inject the generated repository beans (the processor also runs in plain builds; the generated `…Impl` is a CDI bean).
- H2 or PostgreSQL. H2 is the default dialect; select PostgreSQL with `-Afluda.dialect=postgresql`.

## Adding dependencies

Add the runtime API as a normal dependency, and the processor (plus the optional query-by-method-name parser) to the **annotation processor path**:

```xml
<dependencies>
    <!-- Runtime API: entity-model annotations, metadata contracts, SQL rendering -->
    <dependency>
        <groupId>io.github.fludakit</groupId>
        <artifactId>fluda-data-core</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>

    <!-- Jakarta Data API (@Repository, CrudRepository, @Find, @By, ...) -->
    <dependency>
        <groupId>jakarta.data</groupId>
        <artifactId>jakarta.data-api</artifactId>
        <version>1.0.1</version>
    </dependency>

    <!-- Executes the generated SQL; produces JdbcClient in CDI -->
    <dependency>
        <groupId>io.github.fludakit</groupId>
        <artifactId>fluda-jdbc-cdi</artifactId>
        <version>1.0.0-Beta1</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>io.github.fludakit</groupId>
                        <artifactId>fluda-data-processor</artifactId>
                        <version>1.0.0-SNAPSHOT</version>
                    </path>
                    <!-- OPTIONAL: only needed for query-by-method-name (findBy.../countBy.../deleteBy...) -->
                    <path>
                        <groupId>io.github.fludakit</groupId>
                        <artifactId>fluda-data-qbn</artifactId>
                        <version>1.0.0-SNAPSHOT</version>
                    </path>
                    <path>
                        <groupId>jakarta.enterprise</groupId>
                        <artifactId>jakarta.enterprise.cdi-api</artifactId>
                        <version>4.1.0</version>
                    </path>
                </annotationProcessorPaths>
                <!-- Uncomment to target PostgreSQL instead of the default H2 dialect -->
                <!--
                <compilerArgs>
                    <arg>-Afluda.dialect=postgresql</arg>
                </compilerArgs>
                -->
            </configuration>
        </plugin>
    </plugins>
</build>
```

!!! warning "Query-by-method-name needs the `qbn` processor"
    `fluda-data-qbn` is an **optional** annotation processor dependency. A repository that derives queries from method names (`findBy…`, `countBy…`, `deleteBy…`) must declare it on the processor path. Without it the processor reports a compile error naming the dependency, rather than silently generating a predicate-less query.

## Mapping an entity

Annotate the type with `@Entity` and mark its identifier field with `@Identity`:

```java
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
```

Fields map to snake-cased columns (`title` → `title`, a hypothetical `dueDate` → `due_date`) unless the type is a value object or association — see [Applying DDD](ddd.md). Enum fields round-trip through the generated mapper by name.

## Declaring a repository

Extend one of the Jakarta Data repository supertypes with a concrete entity and identifier type. An empty interface already gives you the full set of built-in CRUD operations:

```java
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

@Repository
public interface TaskRepository extends CrudRepository<Task, Long> {
}
```

At compile time the processor generates `TaskRepositoryImpl` — a CDI bean you can inject directly:

```java
import jakarta.inject.Inject;

@ApplicationScoped
public class TaskService {

    @Inject
    TaskRepository tasks;

    public Task create(String title) {
        Task task = new Task();
        task.setTitle(title);
        task.setStatus(Status.NEW);
        return tasks.insert(task);   // returns the entity with its generated id
    }
}
```

## Lifecycle annotations

Beyond the built-in methods, you can declare your own lifecycle methods and annotate them. These are decided purely on the **method signature** — the method name is free:

| Annotation | Effect |
|------------|--------|
| `@Insert`  | Insert a new row; fails if the row already exists. |
| `@Update`  | Update an existing row. |
| `@Save`    | **Upsert** — update, and insert if no row was updated. |
| `@Delete`  | Delete by entity, by identifier, or by a parameter-derived predicate. |

```java
import jakarta.data.repository.Delete;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Repository;
import jakarta.data.repository.Save;

@Repository
public interface TaskRepository extends CrudRepository<Task, Long> {

    @Insert
    void store(Task task);            // name is arbitrary; the annotation decides

    @Save
    Task persist(Task task);          // upsert: insert or update

    @Delete
    int removeByStatus(Status status); // delete predicate derived from the parameter
}
```

Lifecycle methods accept a single entity, a `List<E>` or an `E[]`, and may return `void`, the entity, a count, or a collection. `save`/`saveAll` follow Jakarta Data upsert semantics: an entity carrying an application-assigned identifier is inserted when the update matches no row.

## Built-in repository methods

Extending `CrudRepository` (or `BasicRepository`) contributes these methods, which the processor implements without any declaration on your part:

| Method | Purpose |
|--------|---------|
| `insert` / `insertAll` | Insert one entity, or a `List`/array of them. |
| `update` / `updateAll` | Update one entity, or a `List`/array of them. |
| `save` / `saveAll` | Upsert one entity, or a `List`/array of them. |
| `delete` / `deleteAll` | Delete a given entity, or a `List`/array of them. |
| `deleteById` | Delete by identifier. |
| `findById` | Find by identifier, returning `Optional<E>`. |
| `findAll` | Stream every entity. |
| `existsById` | Whether a row with the identifier exists. |
| `count` | The total number of entities. |

The declared return type chooses the terminal operation: `List`/`Collection`/`Iterable` materialize a list, `Set` a `LinkedHashSet`, `Stream` a stream, `Optional`/single entity a single result, and `Page`/`CursoredPage` the paged readers.

## Query by method name

With `fluda-data-qbn` on the processor path, an **unannotated** method whose name is the query is analyzed at compile time. This is the counterpart to the annotation-driven methods above: the name carries the predicates, connectors, operators, ordering and limits.

```java
@Repository
public interface TaskRepository extends CrudRepository<Task, Long> {

    List<Task> findByStatus(Status status);

    List<Task> findByStatusOrderByTitleAsc(Status status);

    List<Task> findByTitleContainingAndStatus(String text, Status status);

    long countByStatus(Status status);

    void deleteByStatus(Status status);

    List<Task> findFirst10ByStatusOrderByTitleDesc(Status status);
}
```

The parser honours the Jakarta Data query-by-method-name rules:

- **Connectors** — `And` / `Or`, evaluated with `And` binding tighter than `Or` (SQL precedence).
- **Negation** — `Not`, and the negated operators `NotLike`, `NotIn`, `NotBetween`, `NotContains`, `NotStartsWith`, `NotEndsWith`, `NotNull`.
- **Operators** — `Like`, `In`, `Between`, `GreaterThan`, `LessThan`, `GreaterThanEqual`, `LessThanEqual`, `Contains`, `StartsWith`, `EndsWith`, `Null`, and bare equality.
- **Subject keywords** — `First<n>` (a row limit) and `Distinct`.
- **Ordering** — `OrderBy<Property><Asc|Desc>`.

A property named in a method must resolve on the entity; a name that does not is a **compile error**, not a silent fallback to a guessed column. Traversing a to-one relation (`findByCustomerName`) generates join-aware SQL.

### Annotation-driven finders

When the method name should not be analyzed, use the Jakarta Data annotations instead. `@Find` builds criteria from `@By` (or the parameter names when `@By` is absent), `@OrderBy` sorts, and `@Query` supplies native SQL:

```java
import jakarta.data.page.CursoredPage;
import jakarta.data.page.PageRequest;
import jakarta.data.repository.By;
import jakarta.data.repository.Find;
import jakarta.data.repository.OrderBy;
import jakarta.data.repository.Query;

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

    @Query("SELECT * FROM customer WHERE email = :email")
    Optional<Customer> byEmail(String email);
}
```

`@Find`/`@Query` methods are accepted exactly as declared; a relation traversal must be written in `@By` (for example `@By("customer.name")`) rather than spelled into the name.

## Where to next

- Model aggregates, embed value objects and reference other aggregates in [Applying DDD](ddd.md).
- Learn how the generated SQL is executed in the [JDBC Client](https://fludakit.github.io/jdbc-client/) documentation, and how to demarcate transactions in the [Transaction](https://fludakit.github.io/tx/) documentation.
