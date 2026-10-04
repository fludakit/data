# FluDa Feature Template

Project template for creating new FluDa Kit feature modules. Provides a ready-to-use Maven multi-module project with CI/CD pipelines, code style configuration, and publishing to both GitHub Packages (SNAPSHOTs) and Maven Central (releases).

## What's included

- **Parent POM** inheriting from `fludakit-parent` — all shared dependencies, plugins, and the JDK 21+ enforcer are pre-configured.
- **Core submodule** with a placeholder class, test, and JPMS module-info generation via moditect.
- **GitHub Actions workflows** — build + SNAPSHOT publish on push to `main`, and manual Maven Central release via `workflow_dispatch`.
- **Maven wrapper** (`mvnw` / `mvnw.cmd`) — no need to pre-install Maven.
- **Code style** — `.editorconfig` with IntelliJ IDEA settings (4-space indent, 120-column width, CRLF, no wildcard imports).
- **Standard project files** — `.gitignore`, `CONTRIBUTING.md`, `LICENSE` (Apache 2.0).

## Prerequisites

- **JDK 21+** (JDK 25 required for releasing to Maven Central).
- **Git** for version control.
- A **GitHub repository** under the `fludakit` org (create it before publishing).

## Step-by-step setup

### 1. Create the new repository

Create a new repository under `fludakit` on GitHub (e.g., `fludakit/my-module`). Do **not** initialize it with a README or `.gitignore`.

### 2. Copy the template

```bash
# From the fludakit monorepo root
cp -r template /tmp/my-module
cd /tmp/my-module
git init
git remote add origin git@github.com:fludakit/my-module.git
```

Or clone the template directory directly into a new repo:

```bash
git clone git@github.com:fludakit/my-module.git
cd my-module
# Copy template contents (excluding the template's .git if any)
cp -r ../template/{.,}* . 2>/dev/null
```

### 3. Rename all placeholders

Throughout the project, replace `feature` with your module name. Using `my-module` as an example:

**In `pom.xml` (parent):**

| Placeholder | Replace with |
|---|---|
| `fluda-feature-parent` | `fluda-my-module-parent` |
| `FluDa :: Feature` | `FluDa :: My Module` |
| `https://github.com/fludakit/feature` | `https://github.com/fludakit/my-module` |
| `fludakit/feature.git` (in `<scm>`) | `fludakit/my-module.git` |
| `https://maven.pkg.github.com/fludakit/feature` | `https://maven.pkg.github.com/fludakit/my-module` |
| `TODO: Describe your feature module.` | Your module's description |

**In `core/pom.xml`:**

| Placeholder | Replace with |
|---|---|
| `fluda-feature-core` | `fluda-my-module-core` |
| `FluDa :: Feature :: Core` | `FluDa :: My Module :: Core` |
| `fluda.feature.core` (module name) | `fluda.my.module.core` |
| `io.github.fludakit.feature` (in `<exports>`) | `io.github.fludakit.mymodule` |

**In Java sources** (`core/src/main/java` and `core/src/test/java`):

- Rename directory `io/github/fludakit/feature/` → `io/github/fludakit/mymodule/`
- Update `package io.github.fludakit.feature;` → `package io.github.fludakit.mymodule;`
- Rename `Feature.java` → your main class name
- Rename `FeatureTest.java` → match

**In `README.md` and `CONTRIBUTING.md`:**

- Replace all `feature` references with your module name
- Replace the "How to use" section with your module's actual description and documentation
- Update repository links

### 4. Add more submodules (optional)

To add a `cdi` or `config` submodule alongside `core`:

```bash
mkdir -p cdi/src/main/java/io/github/fludakit/mymodule/cdi
mkdir -p cdi/src/test/java/io/github/fludakit/mymodule/cdi
```

Create `cdi/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xmlns="http://maven.apache.org/POM/4.0.0"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.github.fludakit</groupId>
        <artifactId>fluda-my-module-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>fluda-my-module-cdi</artifactId>
    <packaging>jar</packaging>

    <name>FluDa :: My Module :: CDI</name>
    <description>CDI integration for My Module.</description>

    <dependencies>
        <dependency>
            <groupId>io.github.fludakit</groupId>
            <artifactId>fluda-my-module-core</artifactId>
        </dependency>
        <dependency>
            <groupId>jakarta.enterprise</groupId>
            <artifactId>jakarta.enterprise.cdi-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.moditect</groupId>
                <artifactId>moditect-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <id>add-module-info</id>
                        <phase>package</phase>
                        <goals>
                            <goal>add-module-info</goal>
                        </goals>
                        <configuration>
                            <module>
                                <moduleInfo>
                                    <name>fluda.my.module.cdi</name>
                                    <exports>
                                        io.github.fludakit.mymodule.cdi;
                                    </exports>
                                </moduleInfo>
                            </module>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

Then add `<module>cdi</module>` to the parent `pom.xml`.

### 5. Build and test

```bash
./mvnw clean install
```

This compiles, runs tests, generates JPMS module-info descriptors, and installs to your local Maven repository.

### 6. Commit and push

```bash
git add .
git commit -m "feat: initial project setup from template"
git branch -M main
git push -u origin main
```

## CI/CD

### Build & SNAPSHOT publish (`build.yml`)

Triggered automatically on every push to `main`:

1. **build** job — checks out, installs JDK 21, runs `mvn install` (compile + test).
2. **publish** job — runs after build succeeds, deploys SNAPSHOT to GitHub Packages via `mvn deploy`.

SNAPSHOTs are available at:
```
https://maven.pkg.github.com/fludakit/my-module
```

Consumers need a GitHub token in their `~/.m2/settings.xml` to resolve these.

### Maven Central release (`maven-publish.yml`)

Triggered **manually** via GitHub Actions → "Run workflow":

1. Enter the release version (e.g., `1.0.0`).
2. Enter the next development version (e.g., `1.1.0-SNAPSHOT`).

The workflow will:
- Set the release version in all POMs.
- Commit, tag, and push the tag.
- Build with `-P release` (signs artifacts with GPG, generates javadoc/sources, publishes to Maven Central via `central-publishing-maven-plugin`).
- Bump to the next SNAPSHOT version and push.

**Required GitHub secrets:**

| Secret | Description |
|---|---|
| `GPG_SIGNING_KEY` | GPG private key (ASCII-armored) for artifact signing |
| `GPG_SIGNING_KEY_PASSWORD` | Passphrase for the GPG key |
| `CENTRAL_USERNAME` | Maven Central (Sonatype) username |
| `CENTRAL_TOKEN` | Maven Central (Sonatype) password/token |

## Publishing strategy

| Version type | Target | Trigger |
|---|---|---|
| `*-SNAPSHOT` | GitHub Packages | Automatic on push to `main` |
| Milestone / Release | Maven Central | Manual via `workflow_dispatch` |

## Project structure

```
.
├── .editorconfig               # IntelliJ IDEA code style
├── .gitignore                  # Java/Maven/IDE ignores
├── .github/
│   └── workflows/
│       ├── build.yml           # Build + SNAPSHOT publish
│       └── maven-publish.yml   # Manual Maven Central release
├── .mvn/
│   └── wrapper/
│       └── maven-wrapper.properties
├── mvnw                        # Maven wrapper (Unix)
├── mvnw.cmd                    # Maven wrapper (Windows)
├── pom.xml                     # Parent POM (extends fludakit-parent)
├── README.md                   # This file
├── CONTRIBUTING.md             # Contribution guide
├── LICENSE                     # Apache License 2.0
└── core/                       # Core submodule
    ├── pom.xml
    └── src/
        ├── main/java/          # Production code
        └── test/java/          # Tests
```

## Dependencies managed by the parent

The `fludakit-parent` POM already declares these in `<dependencyManagement>` — just add them without `<version>`:

- `jakarta.jakartaee-bom` (Jakarta EE 11 API)
- `microprofile` (MicroProfile 7.1)
- `junit-bom` (JUnit 5)
- `com.h2database:h2`
- `org.postgresql:postgresql`
- `org.jboss.weld:weld-junit5`
- `io.smallrye.config:smallrye-config`
- `com.zaxxer:HikariCP`
- `org.hibernate.orm:hibernate-core`

Plus all sibling FluDa modules (`fluda-sql-init-*`, `fluda-jdbc-client-*`, `fluda-tx-*`).

## Related repositories

- [fludakit/parent](https://github.com/fludakit/parent) — Parent POM with shared dependencies and plugins.
- [fludakit/bom](https://github.com/fludakit/bom) — Bill of Materials for FluDa modules.
- [fludakit/examples](https://github.com/fludakit/examples) — Runnable example applications.
- [fludakit/fludakit.github.io](https://github.com/fludakit/fludakit.github.io) — Reference documentation site.

## License

Apache License, Version 2.0. See [LICENSE](LICENSE).
