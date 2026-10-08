# FluDa Data

[![Build](https://github.com/fludakit/data/actions/workflows/build.yml/badge.svg)](https://github.com/fludakit/data/actions/workflows/build.yml)

A fluent data-access toolkit for Jakarta EE / CDI applications.

## Modules

- `fluda-data-core`: the core API and supporting types.

## Building

```bash
./mvnw clean install
```

## Documentation

See the [reference documentation site](https://fludakit.github.io/) for getting started and full API reference.

## Contributing

Contributions are welcome — issues, pull requests, and feature suggestions are all encouraged. See [CONTRIBUTING.md](CONTRIBUTING.md) for how to build the project and submit changes.

## License

Apache License, Version 2.0. See [LICENSE](LICENSE).

## Breaking changes in 1.0.0-SNAPSHOT (navigable metamodel iteration)

- `EntityDescriptor.columns()` no longer has a derived default implementation. Implementers must
  supply the explicit column view (the generated `<Entity>Metadata` does; hand-written test
  descriptors must too).
- `EntityDescriptor.attributes()` no longer flattens embedded leaves. `attribute("address.city")`
  does not resolve — dot paths resolve through `EntityDescriptor.columnRef(String)`, which also
  reports the joins a path contributes.
- Method-name finders whose predicate path traverses one relation (`findByCustomerName`) now
  generate join-aware SQL. `findByCustomerId`-style names traverse the relation's id instead of
  comparing the owner's FK column directly (Jakarta Data 1.0.1 traversal semantics); orphan FK
  rows are consequently excluded from such results.
- Path-depth asymmetry (known, documented): compile-side method-name resolution accepts at most
  ONE relation hop (multi-hop paths emit a NOTE and keep the legacy unqualified column); the
  runtime `columnRef` used by `Order.by`/`Sort` is unbounded. The same dotted string can therefore
  be a working sort and a rejected finder name. Cursor keys compound this: the generated
  value-extraction switch answers one hop only, so a deeper path may sort under OFFSET paging but
  throws `IllegalArgumentException` naming the exact path when a CURSOR page request builds its key.
- Cursors for relation-sorted paged reads now end with the root id key.
- `deleteBy…` names whose predicate path traverses one relation now render correlated `EXISTS`
  semi-joins — `deleteByCustomerName` emits `DELETE FROM "order" WHERE EXISTS (SELECT 1 FROM
  "customer" "customer" WHERE "customer"."id" = "order"."customer_id" AND "customer"."name" = :name)`
  — and delete exactly the roots the matching `findBy…` read returns. Before, they were NOTE-degraded
  to a join-free predicate on the lower-snake column, which could match the wrong rows. Relation-free
  delete rendering is unchanged; multi-hop paths stay NOTE-rejected (legacy fallback).
