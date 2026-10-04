# Contributing

Thanks for your interest in contributing to **FluDa Feature**!

This document describes how to set up the project, make changes, and submit them for review.

## Ways to contribute

- **Report bugs** or **request features** by opening a [GitHub issue](https://github.com/fludakit/feature/issues).
- **Fix bugs or add features** by opening a pull request (see below).

## Prerequisites

- JDK 21 or later (JDK 25 is required to publish a release to Maven Central).
- Maven 3.9+ (or use the included wrapper `./mvnw`).

## Building

Clone the repository and build everything:

```bash
git clone https://github.com/fludakit/feature.git
cd feature
./mvnw clean install
```

## Project structure

```
.
├── core/                   # fluda-feature-core — core functionality
└── ...                     # add more modules as needed
```

## Code style

Formatting is driven by the committed [`.editorconfig`](.editorconfig) (IntelliJ IDEA settings):

- 4-space indentation, 120-column maximum line width, CRLF line endings.
- No wildcard imports — one class per import.
- Import layout: project classes, then `java.**` / `javax.**` / `jakarta.**`, then static imports.

Use IntelliJ IDEA's **Reformat Code** action (which reads `.editorconfig`) before committing, so formatting changes are kept out of functional diffs.

## Testing

Run the unit tests:

```bash
./mvnw test
```

## Commits and pull requests

- Follow [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `docs:`,
  `chore:`, `ci:`, `refactor:` (see the existing git history for examples).
- Keep each pull request to a single logical change, and reference any related issue.
- Add or update tests and documentation alongside the change.

## Related repositories

- [fludakit/jdbc-client](https://github.com/fludakit/jdbc-client) — Fluent JDBC client for Jakarta EE / CDI.
- [fludakit/sql-init](https://github.com/fludakit/sql-init) — SQL schema initialization for Jakarta EE / CDI.
- [fludakit/tx](https://github.com/fludakit/tx) — Transaction support for Jakarta EE / CDI.
- [fludakit/bom](https://github.com/fludakit/bom) — Bill of Materials for FluDa modules.
- [fludakit/examples](https://github.com/fludakit/examples) — Runnable example applications.
- [fludakit/fludakit.github.io](https://github.com/fludakit/fludakit.github.io) — Reference documentation site.

## License

All contributions are licensed under the [Apache License, Version 2.0](LICENSE). By contributing you
agree that your contributions are licensed under the same terms.
