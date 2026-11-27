# Applying DDD

This page covers modeling with aggregate roots, value objects and cross-aggregate associations using FluDa Data's DDD-aligned annotations.

## Core concepts

FluDa Data provides annotations that map directly to Domain-Driven Design building blocks:

| DDD Concept | FluDa Annotation | Description |
|-------------|------------------|-------------|
| **Aggregate Root** | `@AggregateRoot` | The entry point to an aggregate — a cluster of objects modified as a unit. The root controls all changes to its owned entities and value objects. |
| **Entity** | `@Entity` | An object defined by its identity rather than its attributes. Entities within an aggregate have their own table and a foreign key back to the root. |
| **Value Object** | `@ValueObject` | An immutable object defined entirely by its attributes — no identity. Value objects are embedded into the owning entity's table. |

These annotations let you model your domain in plain Java while FluDa handles the persistence mapping. The design follows DDD principles: aggregates are transactional boundaries, value objects are immutable and embedded, and entities have their own identity and lifecycle within the aggregate.

## Aggregate Root

An aggregate is a cluster of objects that is modified as a unit through a single entry point: the **aggregate root**. Mark the root type with `@AggregateRoot`. Everything the root owns — embedded value objects, composed child entities and element collections — is persisted and loaded with it, and a `save` of the root cascades to the objects it owns.

```java
import io.github.fludakit.data.annotation.AggregateRoot;
import io.github.fludakit.data.annotation.Identity;

@AggregateRoot
public class Profile {
    @Identity Long id;
    String fullName;

    Address address;                       // value object  -> flattened into this table
    List<WorkExperience> workHistory;      // entity collection -> own table, fk profile_id
    List<Education> education;             // entity collection -> own table, fk profile_id
    List<ContactPoint> contacts;           // value-object element collection -> child table
    // getters and setters ...
}
```

The owner's fields are classified by the type they reference:

| Field | Type | Storage |
|-------|------|---------|
| `address` | `@ValueObject` | **Embedded** — its leaves are flattened into the root's own table (`address.city`, `address.zip`, …). A value object has no identity of its own. |
| `contacts` | `List<` `@ValueObject` `>` | **Element collection** — a child table (`profile_contacts`) keyed by the root's foreign key plus the flattened value-object columns. |
| `workHistory`, `education` | `List<` `@Entity` `>` | **Composition** — each target is an entity with its own identity and its own table, carrying a foreign key back to the root (`profile_id`). |

## Value objects

A `@ValueObject` is immutable in spirit and defined entirely by its attributes — it has no identity. Embedded value objects may nest (an `Address` may hold a `GeoPoint`), and their leaves are flattened into the owning table. Because they are part of the root's row, you can sort and filter through them with a dot path:

```java
@Find
@OrderBy("address.city")
List<Profile> orderedByCity();
```

## Composition children

A composed `@Entity` (such as `WorkExperience`) manages its own table and identity, and holds a reference back to the root. The child's bare inverse field maps to the same foreign key the aggregate loader walks:

```java
@Entity
public class WorkExperience {
    @Identity Long id;
    String company;
    int yearStart;
    int yearEnd;
    Profile profile;   // inverse reference -> profile_id
    // ...
}
```

Querying **through** a composition collection renders an `EXISTS` semi-join, so the root never fans out into duplicate rows and the count stays correct:

```java
@Find
List<Profile> findByWorkHistoryCompany(@By("workHistory.company") String company);

long countByWorkHistoryCompany(String company);   // query-by-method-name equivalent
```

## Associations: referencing another aggregate

Not every relationship is composition. When a field points at an entity that belongs to a **different** aggregate (typically another bounded context), mark it `@Association`. An association is persisted as a **foreign key only**:

- On write, the referenced entity's identifier is extracted into the `<field>_id` column; the referenced entity itself is **never cascaded**.
- On read, the reference is **not** eagerly resolved — the field is left `null` and the application resolves it lazily when it needs to.

```java
@Entity
public class Order {
    @Identity Long id;
    BigDecimal amount;
    @Association Customer customer;   // persisted as customer_id, not cascaded
    List<OrderItem> orderItems;       // composition, owned by this aggregate
    // ...
}
```

This keeps aggregate boundaries explicit: `Order` owns its `orderItems`, but only *points at* a `Customer` that some other aggregate owns and saves.
