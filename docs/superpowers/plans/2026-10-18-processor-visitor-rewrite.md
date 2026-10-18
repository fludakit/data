# Processor Visitor Rewrite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite the annotation processor from scratch using Micronaut Data-style visitor pattern, producing the same generated artifacts (with renamed conventions) and passing all existing integration tests.

**Architecture:** Three-phase pipeline — TypeMappingVisitor scans entities/VOs/aggregates into a TypeMapping cache, RepositoryVisitor processes each @Repository interface using RepositoryContext + MethodContext, and separate emitters generate code. Support package is minimal (Diagnostics only).

**Tech Stack:** Java 17+, JavaPoet, Jakarta Data 1.0, Jakarta CDI 4.1, H2/PostgreSQL dialects, existing core module (TypeMapping, SqlBuilder, ColumnRef, ColumnPlacement, JoinEdge, QueryDefinition).

**Spec:** `docs/superpowers/specs/2026-10-18-processor-visitor-rewrite-design.md`

## Global Constraints

- Branch: `refactor/processor-visitor-rewrite` from `main` — clean break, no code copied from old processor
- Generated naming: `XXXTypeModel` (not XXXMetadata), `Repository_` (not RepositoryImpl), `RepositoryQueryModel` (not RepositoryQueries)
- Identifier annotation: `@Identity` (not `@Id`)
- Value objects: `@ValueObject` (no separate `@Embeddable`/`@ElementCollection`)
- Associations: `@Association` for non-composing references; composition = `Prop.Reference` with `composition=true`
- Support package: only `Diagnostics.java` — no MethodResults, JoinEdges, QueryInvocations, GeneratedKeyCoercion, Props, MemberNames
- CDI: BOM-managed CDI 4.1 + Weld 6.x; never adopt jakarta.cdi:jakarta.cdi-api 5.0.0
- SQL: dialect-rendered literal clauses + Java-computed values over bind params/DB arithmetic
- All existing integration tests must pass with renamed artifacts

---

### Task 1: Branch Setup + Scaffolding

**Files:**
- Create: `processor/src/main/java/io/github/fludakit/data/processor/RepositoryProcessor.java`
- Create: `processor/src/main/java/io/github/fludakit/data/processor/support/Diagnostics.java`
- Create: `processor/src/main/java/io/github/fludakit/data/processor/package-info.java`
- Delete: all existing files under `processor/src/main/java/io/github/fludakit/data/processor/` (the old processor)

**Interfaces:**
- Consumes: nothing (fresh start)
- Produces: empty RepositoryProcessor skeleton, Diagnostics utility

**Context:** Start by creating the new branch from main, deleting the old processor files, and creating the scaffolding.

- [ ] **Step 1: Create the new branch from main**

```bash
git checkout main
git checkout -b refactor/processor-visitor-rewrite
```

- [ ] **Step 2: Delete all old processor files**

```bash
git rm -r processor/src/main/java/io/github/fludakit/data/processor/
```

- [ ] **Step 3: Create Diagnostics**

```java
// processor/src/main/java/io/github/fludakit/data/processor/support/Diagnostics.java
package io.github.fludakit.data.processor.support;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

public final class Diagnostics {
    private final Messager messager;

    public Diagnostics(Messager messager) {
        this.messager = messager;
    }

    public void error(String message, Element element) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    public void warn(String message, Element element) {
        messager.printMessage(Diagnostic.Kind.WARNING, message, element);
    }

    public void note(String message, Element element) {
        messager.printMessage(Diagnostic.Kind.NOTE, message, element);
    }
}
```

- [ ] **Step 4: Create RepositoryProcessor skeleton**

```java
// processor/src/main/java/io/github/fludakit/data/processor/RepositoryProcessor.java
package io.github.fludakit.data.processor;

import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.sql.H2Dialect;
import io.github.fludakit.data.sql.SqlDialect;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.Set;

@SupportedAnnotationTypes({
    "jakarta.data.repository.Repository",
    "io.github.fludakit.data.annotation.Entity",
    "io.github.fludakit.data.annotation.ValueObject",
    "io.github.fludakit.data.annotation.AggregateRoot"
})
@SupportedOptions("fluda.dialect")
public class RepositoryProcessor extends AbstractProcessor {

    private Diagnostics diagnostics;
    private Types types;
    private Elements elements;
    private Filer filer;
    private SqlDialect dialect;
    private TypeMappingVisitor typeMappingVisitor;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.diagnostics = new Diagnostics(processingEnv.getMessager());
        this.types = processingEnv.getTypeUtils();
        this.elements = processingEnv.getElementUtils();
        this.filer = processingEnv.getFiler();
        this.dialect = resolveDialect(processingEnv.getOptions().get("fluda.dialect"));
        this.typeMappingVisitor = new TypeMappingVisitor(types, elements, diagnostics);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) return false;

        // Phase 1: Scan types
        typeMappingVisitor.scanRound(roundEnv);

        // Phase 2: Visit repositories
        for (Element element : roundEnv.getElementsAnnotatedWith(jakarta.data.repository.Repository.class)) {
            if (element.getKind() != javax.lang.model.element.ElementKind.INTERFACE) continue;
            TypeElement repository = (TypeElement) element;
            try {
                new RepositoryVisitor(repository, this).visit();
            } catch (RuntimeException e) {
                diagnostics.error(
                    "fluda-data could not generate " + repository.getSimpleName() + "_: "
                        + e.getMessage() + " -- the repository shape is not supported",
                    repository);
            }
        }
        return false;
    }

    private SqlDialect resolveDialect(String option) {
        if ("postgresql".equalsIgnoreCase(option)) return io.github.fludakit.data.sql.PostgreSqlDialect.INSTANCE;
        return H2Dialect.INSTANCE;
    }

    // Package-private accessors for visitors/emitters
    TypeMappingVisitor typeMappingVisitor() { return typeMappingVisitor; }
    public SqlDialect dialect() { return dialect; }
    public Filer filer() { return filer; }
    public Diagnostics diagnostics() { return diagnostics; }
    public Types types() { return types; }
    public Elements elements() { return elements; }
}
```

- [ ] **Step 5: Create package-info.java**

```java
// processor/src/main/java/io/github/fludakit/data/processor/package-info.java
/**
 * Jakarta Data annotation processor using visitor pattern.
 *
 * <p>Three-phase pipeline:
 * <ol>
 *   <li>{@link TypeMappingVisitor} scans entities, value objects, and aggregates</li>
 *   <li>{@link RepositoryVisitor} processes each repository interface</li>
 *   <li>Emitters generate code from resolved contexts</li>
 * </ol>
 */
package io.github.fludakit.data.processor;
```

- [ ] **Step 6: Create stub classes so it compiles**

Create empty stubs for `TypeMappingVisitor` and `RepositoryVisitor` so the processor compiles:

```java
// processor/src/main/java/io/github/fludakit/data/processor/TypeMappingVisitor.java
package io.github.fludakit.data.processor;

import io.github.fludakit.data.processor.support.Diagnostics;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.Set;

final class TypeMappingVisitor {
    TypeMappingVisitor(Types types, Elements elements, Diagnostics diagnostics) {}
    void scanRound(RoundEnvironment roundEnv) {}
}
```

```java
// processor/src/main/java/io/github/fludakit/data/processor/RepositoryVisitor.java
package io.github.fludakit.data.processor;

import javax.lang.model.element.TypeElement;

final class RepositoryVisitor {
    RepositoryVisitor(TypeElement repository, RepositoryProcessor host) {}
    void visit() {}
}
```

- [ ] **Step 7: Verify compilation**

```bash
mvn compile -pl processor -q
```

Expected: BUILD SUCCESS (stubs compile, no functionality yet)

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(processor): scaffold visitor-pattern processor with Diagnostics"
```

---

### Task 2: TypeMappingVisitor — Entity Scanning

**Files:**
- Modify: `processor/src/main/java/io/github/fludakit/data/processor/TypeMappingVisitor.java`
- Create: `processor/src/test/java/io/github/fludakit/data/processor/TypeMappingVisitorTest.java`

**Interfaces:**
- Consumes: `Types`, `Elements`, `Diagnostics`, `RoundEnvironment`
- Produces: `TypeMappingVisitor` with `scanRound()`, `visitType()`, `get()`, `managedEntities()`

**Context:** The TypeMappingVisitor scans `@Entity`, `@ValueObject`, `@AggregateRoot` annotated types and builds `TypeMapping` instances. It caches by FQN and handles cycles.

- [ ] **Step 1: Write the failing test**

```java
// processor/src/test/java/io/github/fludakit/data/processor/TypeMappingVisitorTest.java
package io.github.fludakit.data.processor;

import io.github.fludakit.data.metadata.EntityTypeMapping;
import io.github.fludakit.data.metadata.TypeMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TypeMappingVisitor tests use compile-testing (google compile-testing or javax.tools)
 * to feed source strings through the processor and verify the TypeMapping cache.
 *
 * For now, we test the visitor's cache behavior directly.
 */
class TypeMappingVisitorTest {

    @Test
    void getReturnsNullForUnknownFqn() {
        // Visitor with no scanned types returns null for unknown FQN
        // This tests the cache-miss path
        // Implementation depends on compile-testing setup
    }
}
```

- [ ] **Step 2: Implement TypeMappingVisitor**

Replace the stub with the full implementation. The visitor:
1. Scans `@Entity`, `@AggregateRoot`, `@ValueObject` annotated types from the round
2. For each type, inspects fields and classifies them into `Prop` variants
3. Builds the appropriate `TypeMapping` record (`EntityTypeMapping`, `AggregateTypeMapping`, `EmbeddedValueObjectMapping`, `ElementCollectionValueObjectMapping`)
4. Caches by FQN; detects cycles via an in-progress set

Key implementation details:
- `@Identity` marks the identifier field → `Prop.Id`
- `@ValueObject` field → `Prop.Embedded` pointing to `EmbeddedValueObjectMapping`
- `List<@ValueObject>` → `Prop.ElementCollection` pointing to `ElementCollectionValueObjectMapping`
- `@Association` field → `Prop.Reference` with `composition=false`
- Bare entity field on `@AggregateRoot` → `Prop.Reference` with `composition=true`
- `List<@Entity>` → `Prop.EntityCollection`
- Plain fields (String, int, enum, etc.) → `Prop.Scalar`
- Column names derived from field names (lowercase underscore convention) or `@Column` annotation

The full implementation is ~300-400 lines. Reference the old `TypeModelBuilder` + `TypeModelResolver` on the `refactor/processor-monolithic-redesign` branch for the field classification logic, but rewrite it cleanly in a single class.

- [ ] **Step 3: Run tests**

```bash
mvn test -pl processor -q
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(processor): implement TypeMappingVisitor for entity/VO/aggregate scanning"
```

---

### Task 3: RepositoryContext + MethodContext

**Files:**
- Create: `processor/src/main/java/io/github/fludakit/data/processor/context/RepositoryContext.java`
- Create: `processor/src/main/java/io/github/fludakit/data/processor/context/MethodContext.java`

**Interfaces:**
- Consumes: `TypeMapping`, `ClassName`, `Operation`, `ResultKind`, `Predicate`, `SortSpec`, `ParamBinding`, `PagingMode`
- Produces: `RepositoryContext` and `MethodContext` used by all emitters

- [ ] **Step 1: Create RepositoryContext**

```java
// processor/src/main/java/io/github/fludakit/data/processor/context/RepositoryContext.java
package io.github.fludakit.data.processor.context;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.TypeMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RepositoryContext {
    private final TypeMapping<?> entity;
    private final boolean crud;
    private final boolean spec;
    private final ClassName rowMapperType;
    private final String repositoryFqn;

    // Emission state (mutable, appended by emitters)
    private final Map<String, String> constants = new LinkedHashMap<>();
    private final List<MethodSpec> auxMethods = new ArrayList<>();
    private final Map<String, Integer> queryDefinitionNames = new LinkedHashMap<>();

    public RepositoryContext(TypeMapping<?> entity, boolean crud, boolean spec,
                             ClassName rowMapperType, String repositoryFqn) {
        this.entity = entity;
        this.crud = crud;
        this.spec = spec;
        this.rowMapperType = rowMapperType;
        this.repositoryFqn = repositoryFqn;
    }

    public TypeMapping<?> entity() { return entity; }
    public boolean crud() { return crud; }
    public boolean spec() { return spec; }
    public ClassName rowMapperType() { return rowMapperType; }
    public String repositoryFqn() { return repositoryFqn; }
    public Map<String, String> constants() { return constants; }
    public List<MethodSpec> auxMethods() { return auxMethods; }
    public Map<String, Integer> queryDefinitionNames() { return queryDefinitionNames; }

    /** Register a SQL constant, deduplicating by content. Returns the constant name. */
    public String registerConstant(String base, String sql) {
        String name = base;
        int suffix = 2;
        while (constants.containsKey(name) && !constants.get(name).equals(sql)) {
            name = base + "_" + suffix++;
        }
        constants.put(name, sql);
        return name;
    }

    /** Generate a unique query definition name. */
    public String uniqueQueryName(String base) {
        Integer count = queryDefinitionNames.get(base);
        if (count == null) {
            queryDefinitionNames.put(base, 1);
            return base;
        }
        queryDefinitionNames.put(base, count + 1);
        return base + "_" + (count + 1);
    }
}
```

- [ ] **Step 2: Create MethodContext**

```java
// processor/src/main/java/io/github/fludakit/data/processor/context/MethodContext.java
package io.github.fludakit.data.processor.context;

import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import java.util.List;

public final class MethodContext {
    private final ExecutableElement method;
    private final String name;
    private final Operation operation;
    private final ResultKind resultKind;
    private final boolean distinct;
    private final List<? extends VariableElement> parameters;
    private final List<ParamBinding> paramBindings;
    private final List<Predicate> predicates;
    private final List<SortSpec> sorts;
    private final PagingMode pagingMode;
    private final String customSql;

    public MethodContext(ExecutableElement method, String name, Operation operation,
                         ResultKind resultKind, boolean distinct,
                         List<? extends VariableElement> parameters,
                         List<ParamBinding> paramBindings,
                         List<Predicate> predicates, List<SortSpec> sorts,
                         PagingMode pagingMode, String customSql) {
        this.method = method;
        this.name = name;
        this.operation = operation;
        this.resultKind = resultKind;
        this.distinct = distinct;
        this.parameters = parameters;
        this.paramBindings = paramBindings;
        this.predicates = predicates;
        this.sorts = sorts;
        this.pagingMode = pagingMode;
        this.customSql = customSql;
    }

    public ExecutableElement method() { return method; }
    public String name() { return name; }
    public Operation operation() { return operation; }
    public ResultKind resultKind() { return resultKind; }
    public boolean distinct() { return distinct; }
    public List<? extends VariableElement> parameters() { return parameters; }
    public List<ParamBinding> paramBindings() { return paramBindings; }
    public List<Predicate> predicates() { return predicates; }
    public List<SortSpec> sorts() { return sorts; }
    public PagingMode pagingMode() { return pagingMode; }
    public String customSql() { return customSql; }
}
```

- [ ] **Step 3: Verify compilation**

```bash
mvn compile -pl processor -q
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(processor): add RepositoryContext and MethodContext"
```

---

### Task 4: RepositoryVisitor — Method Resolution + Skeleton Emission

**Files:**
- Modify: `processor/src/main/java/io/github/fludakit/data/processor/RepositoryVisitor.java`

**Interfaces:**
- Consumes: `TypeMappingVisitor`, `RepositoryContext`, `MethodContext`, `Diagnostics`
- Produces: working `RepositoryVisitor.visit()` that resolves methods and generates an empty `Repository_` class

**Context:** The RepositoryVisitor resolves the entity type, creates a RepositoryContext, iterates abstract methods, creates a MethodContext per method (resolving predicates/sorts via `entity.resolve(path)`), and generates a skeleton `Repository_` class.

- [ ] **Step 1: Implement RepositoryVisitor**

The visitor:
1. Resolves entity type from the repository's generic parameter (e.g., `BookRepository extends DataRepository<Book, Long>` → `Book`)
2. Fetches `TypeMapping` from `TypeMappingVisitor`
3. Determines repository flavour (PLAIN, DATA_REPOSITORY, CRUD_REPOSITORY) by checking which Jakarta Data interfaces the repository extends
4. Creates `RepositoryContext`
5. Iterates abstract methods:
   - For `@Query`-annotated methods: creates MethodContext with `customSql` set
   - For finder methods: resolves predicates from method name (QBN) or `@By` annotations, resolves sorts from `@OrderBy`
   - For write methods: determines operation from annotation (`@Save`, `@Insert`, `@Update`, `@Delete`)
   - For CRUD methods (save, deleteById, findById, etc.): determines operation from method name
6. Generates the `Repository_` class shell with `@ApplicationScoped`, `JdbcClient` field, constructor

Key implementation details:
- Method name parsing: `findByNameAndAge` → predicates on `name` and `age`
- `@By("customer.name")` → predicate on path `customer.name`
- `@OrderBy("name ASC")` → sort on `name` ascending
- Return type determines `ResultKind`: `E` → SINGLE, `Optional<E>` → OPTIONAL, `List<E>` → LIST, `Page<E>` → PAGE, `CursoredPage<E>` → CURSORED_PAGE, etc.
- Paging mode determined by parameter type: `PageRequest` → OFFSET, cursor keys → KEYSET

The full implementation is ~400-500 lines. Reference the old `RepositoryMethodResolver` for method resolution logic.

- [ ] **Step 2: Generate skeleton Repository_ class**

For now, generate a `Repository_` class with just the shell (CDI annotation, JdbcClient field, constructor) and no methods. This verifies the visitor pipeline works end-to-end.

Use JavaPoet to generate the class:
```java
TypeSpec.Builder shell = TypeSpec.classBuilder(repository.getSimpleName() + "_")
    .addModifiers(Modifier.PUBLIC)
    .addAnnotation(ApplicationScoped.class)
    .addSuperinterface(ClassName.get(repository))
    .addField(JdbcClient.class, "jdbcClient", Modifier.PRIVATE, Modifier.FINAL)
    .addMethod(MethodSpec.constructorBuilder()
        .addModifiers(Modifier.INJECT)
        .addParameter(JdbcClient.class, "jdbcClient")
        .addStatement("this.jdbcClient = jdbcClient")
        .build());
```

Write the generated class using `JavaFile.writeTo(filer)`.

- [ ] **Step 3: Verify compilation + run integration tests**

```bash
mvn compile -pl processor -q
mvn test -pl integration-tests -q
```

Expected: integration tests fail (no methods generated yet), but the processor runs without errors and generates empty `Repository_` classes.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(processor): implement RepositoryVisitor with skeleton Repository_ generation"
```

---

### Task 5: RowMapperEmitter

**Files:**
- Create: `processor/src/main/java/io/github/fludakit/data/processor/emitter/RowMapperEmitter.java`

**Interfaces:**
- Consumes: `TypeMapping`, `SqlDialect`
- Produces: `ClassName` of the generated row mapper (or null if not eligible)

**Context:** Generates `XXXRowMapper` classes that implement `RowMapper<Entity>`. Reads columns from `ResultSet` by name, constructs entity via setters.

- [ ] **Step 1: Implement RowMapperEmitter**

The emitter:
1. Checks if all fields are JDBC-safe (scalars, enums, embedded VOs) — if not, returns null
2. Generates a `XXXRowMapper` class implementing `RowMapper<Entity>`
3. `mapRow(ResultSet rs)` method reads each column by name, handles nulls, constructs entity

Key implementation details:
- Scalar fields: `rs.getString("name")`, `rs.getLong("id")`, etc.
- Enum fields: `Enum.valueOf(Status.class, rs.getString("status"))`
- Embedded VO fields: delegate to the VO's own row reader
- Column names from `TypeMapping` props

Reference the old `RowMapperGenerator` + `RowReads` + `EmbeddedValueReader` + `JdbcTypes` for the column reading logic. Consolidate into a single emitter.

- [ ] **Step 2: Wire into RepositoryVisitor**

After creating the RepositoryContext, call `RowMapperEmitter.emit(entity)` and store the result as `rowMapperType` in the context.

- [ ] **Step 3: Verify compilation**

```bash
mvn compile -pl processor -q
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(processor): add RowMapperEmitter for XXXRowMapper generation"
```

---

### Task 6: MetamodelEmitter — _Entity Generation

**Files:**
- Create: `processor/src/main/java/io/github/fludakit/data/processor/emitter/MetamodelEmitter.java`

**Interfaces:**
- Consumes: `TypeMapping`
- Produces: `_Entity` interface + `_EntityMember` navigable classes

**Context:** Generates the Jakarta Data static metamodel: `_Entity` interface with `@StaticMetamodel` annotation, typed attribute fields, and string constants. For each embedded/relation/collection member, generates a separate `_EntityMember` class.

- [ ] **Step 1: Implement MetamodelEmitter**

The emitter generates:
1. `_Entity` interface:
   - `@StaticMetamodel(Entity.class)` annotation
   - `String` constants for each property name (e.g., `String NAME = "name"`)
   - Typed attribute fields: `IdAttribute`, `TextScalarAttribute`, `SortableScalarAttribute`
   - Navigable fields for embedded/relation/collection members

2. `_EntityMember` classes:
   - For `Prop.Embedded`: implements `EmbeddableAttribute<Entity, VO>`
   - For `Prop.Reference`: implements `EntityAttribute<Entity, Target>`
   - For `Prop.EntityCollection`: implements `ListAttribute<Entity, Target>`
   - For `Prop.ElementCollection`: implements `ListAttribute<Entity, VO>`
   - Each exposes leaf fields with full dot paths (e.g., `"address.city"`)

Reference the old `MetamodelGenerator` + `NavigationGenerator` + `NavSupport` for the metamodel generation logic.

- [ ] **Step 2: Wire into RepositoryVisitor**

After row mapper generation, call `MetamodelEmitter.emit(entity)` to generate the metamodel classes.

- [ ] **Step 3: Verify compilation**

```bash
mvn compile -pl processor -q
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(processor): add MetamodelEmitter for _Entity static metamodel generation"
```

---

### Task 7: FindEmitter — Plain Finders

**Files:**
- Create: `processor/src/main/java/io/github/fludakit/data/processor/emitter/FindEmitter.java`

**Interfaces:**
- Consumes: `RepositoryContext`, `MethodContext`, `SqlDialect`
- Produces: `MethodSpec` for finder methods

**Context:** The most complex emitter. Generates method implementations for read queries: plain finders, paged, cursored, count, exists, deleteBy.

- [ ] **Step 1: Implement FindEmitter for plain finders**

The emitter:
1. Builds SQL using `SqlBuilder` with the entity's `TypeMapping`
2. Registers SQL as a constant in `RepositoryContext`
3. Generates method body:
   - Execute query via `jdbcClient`
   - Map results using row mapper
   - Handle return type (SINGLE → first row, LIST → all rows, OPTIONAL → nullable)

Key implementation details:
- `entity.resolve(predicate.path())` → `ColumnPlacement` for each predicate
- `SqlBuilder.selectBy(predicates, sorts, limit, null)` → SQL string
- Handle JOIN projections for `Prop.Reference` with `composition=true`
- Handle EXISTS semi-joins for to-many predicate paths

Reference the old `CrudReadEmitter` + `PagingEmitter` + `JoinedProjectionEmitter` for the SQL generation logic.

- [ ] **Step 2: Wire into RepositoryVisitor**

In the emission phase, for each method with `operation == FIND/COUNT/EXISTS/DELETE`, call `FindEmitter.emit(ctx, method)` and add the result to `context.auxMethods()`.

- [ ] **Step 3: Verify compilation + run integration tests**

```bash
mvn compile -pl processor -q
mvn test -pl integration-tests -q
```

Expected: some finder tests start passing (plain finders without paging/joins).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(processor): add FindEmitter for plain finder methods"
```

---

### Task 8: FindEmitter — Paged + Cursored + Count + Exists

**Files:**
- Modify: `processor/src/main/java/io/github/fludakit/data/processor/emitter/FindEmitter.java`

**Interfaces:**
- Consumes: `RepositoryContext`, `MethodContext`, `SqlDialect`
- Produces: `MethodSpec` for paged/cursored/count/exists methods

- [ ] **Step 1: Add paged finder support**

Offset-based paging:
- SQL: `SELECT ... LIMIT ? OFFSET ?`
- Method body: extract `PageRequest` parameter, compute offset, execute query, wrap in `SimplePage`

- [ ] **Step 2: Add cursored finder support**

Keyset/cursor paging:
- SQL: seek clause using cursor keys + `ORDER BY` + `LIMIT ?`
- Method body: extract cursor from `PageRequest`, build seek predicate, execute, wrap in `SimpleCursoredPage`

- [ ] **Step 3: Add count + exists support**

- Count: `SELECT COUNT(*) FROM ... WHERE ...` → return `long`
- Exists: `SELECT EXISTS(SELECT 1 FROM ... WHERE ...)` → return `boolean`

- [ ] **Step 4: Add deleteBy support**

- `DELETE FROM ... WHERE ...` → return `long` (affected rows)
- Handle EXISTS semi-joins for to-many predicate paths

- [ ] **Step 5: Verify compilation + run integration tests**

```bash
mvn compile -pl processor -q
mvn test -pl integration-tests -q
```

Expected: more tests passing (paged finders, count, exists, deleteBy).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(processor): add paged, cursored, count, exists, deleteBy to FindEmitter"
```

---

### Task 9: WriteEmitter

**Files:**
- Create: `processor/src/main/java/io/github/fludakit/data/processor/emitter/WriteEmitter.java`

**Interfaces:**
- Consumes: `RepositoryContext`, `MethodContext`, `SqlDialect`
- Produces: `MethodSpec` for save/insert/update/delete methods

- [ ] **Step 1: Implement WriteEmitter**

The emitter generates:
1. `save()` — upsert semantics (INSERT when absent, UPDATE when present for application-assigned IDs)
2. `insert()` — INSERT with optional generated key return
3. `update()` — UPDATE by ID
4. `deleteById()` — DELETE by ID

Key implementation details:
- Upsert: dialect-specific (H2: `MERGE INTO`, PostgreSQL: `INSERT ... ON CONFLICT`)
- Generated keys: `jdbcClient.insert(sql, entity, KeyHolder.class)`
- Application-assigned IDs: check existence first, then INSERT or UPDATE

Reference the old `WriteMethodEmitter` for the write logic.

- [ ] **Step 2: Wire into RepositoryVisitor**

For methods with `operation == SAVE/INSERT/UPDATE/DELETE`, call `WriteEmitter.emit(ctx, method)`.

- [ ] **Step 3: Verify compilation + run integration tests**

```bash
mvn compile -pl processor -q
mvn test -pl integration-tests -q
```

Expected: CRUD tests start passing.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(processor): add WriteEmitter for save/insert/update/delete methods"
```

---

### Task 10: LifecycleEmitter — Batch Operations

**Files:**
- Create: `processor/src/main/java/io/github/fludakit/data/processor/emitter/LifecycleEmitter.java`

**Interfaces:**
- Consumes: `RepositoryContext`, `MethodContext`, `SqlDialect`
- Produces: `MethodSpec` for batch lifecycle methods

- [ ] **Step 1: Implement LifecycleEmitter**

Generates batch operations:
- `saveAll(Iterable<E>)` — batch upsert
- `deleteAll(Iterable<E>)` — batch delete by ID
- `deleteByIds(Iterable<ID>)` — batch delete by IDs

Key implementation details:
- Loop over the iterable, execute individual SQL statements
- Or use batch JDBC if available

Reference the old `WriteMethodEmitter` batch methods.

- [ ] **Step 2: Wire into RepositoryVisitor**

For batch lifecycle methods, call `LifecycleEmitter.emit(ctx, method)`.

- [ ] **Step 3: Verify compilation + run integration tests**

```bash
mvn compile -pl processor -q
mvn test -pl integration-tests -q
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(processor): add LifecycleEmitter for batch operations"
```

---

### Task 11: TypeModelEmitter — XXXTypeModel Generation

**Files:**
- Create: `processor/src/main/java/io/github/fludakit/data/processor/emitter/TypeModelEmitter.java`

**Interfaces:**
- Consumes: `TypeMapping`
- Produces: `XXXTypeModel` class implementing `TypeMapping<Entity>`

**Context:** Generates the runtime `TypeMapping` implementation that `SqlBuilder` uses at runtime for path-to-column resolution.

- [ ] **Step 1: Implement TypeModelEmitter**

Generates a class like `CustomerTypeModel`:
- Implements `TypeMapping<Customer>` (and `ValueAccess<Customer>`)
- Singleton via `INSTANCE`
- Exposes table name, column constants, `Prop` descriptors
- Implements `resolve(path)` for path-to-column resolution
- Implements `columnRefs()` for the column map

Reference the old `TypeMappingGenerator` for the generation logic.

- [ ] **Step 2: Wire into RepositoryVisitor**

After metamodel generation, call `TypeModelEmitter.emit(entity)` to generate the TypeModel class.

- [ ] **Step 3: Verify compilation**

```bash
mvn compile -pl processor -q
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(processor): add TypeModelEmitter for XXXTypeModel generation"
```

---

### Task 12: QueryModelEmitter — RepositoryQueryModel Generation

**Files:**
- Create: `processor/src/main/java/io/github/fludakit/data/processor/emitter/QueryModelEmitter.java`

**Interfaces:**
- Consumes: `RepositoryContext`
- Produces: `RepositoryQueryModel` class with static `QueryDefinition` fields

- [ ] **Step 1: Implement QueryModelEmitter**

Generates a class like `CustomerRepositoryQueryModel`:
- One `static final QueryDefinition` per query method
- Types: `QueryDefinition.Read`, `QueryDefinition.Count`, `QueryDefinition.Exists`, `QueryDefinition.DeleteById`, `QueryDefinition.Upsert`, `QueryDefinition.Insert`

Reference the old `QueryDefinitionWriter` for the generation logic.

- [ ] **Step 2: Wire into RepositoryVisitor**

In the write phase, call `QueryModelEmitter.emit(repository, context)` to generate the QueryModel class.

- [ ] **Step 3: Verify compilation**

```bash
mvn compile -pl processor -q
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(processor): add QueryModelEmitter for RepositoryQueryModel generation"
```

---

### Task 13: Aggregate Support — Cascade Writes + Hydration Reads

**Files:**
- Modify: `processor/src/main/java/io/github/fludakit/data/processor/emitter/WriteEmitter.java`
- Modify: `processor/src/main/java/io/github/fludakit/data/processor/emitter/FindEmitter.java`
- Create: `processor/src/main/java/io/github/fludakit/data/processor/emitter/HydrationEmitter.java`

**Interfaces:**
- Consumes: `RepositoryContext`, `MethodContext`, `TypeMapping`
- Produces: cascade write logic, hydration read logic

**Context:** Aggregate roots (`@AggregateRoot`) cascade writes to composition children and hydrate to-many collections on read.

- [ ] **Step 1: Add cascade write support to WriteEmitter**

For `@AggregateRoot` entities:
- `save()`: cascade to composition children (Prop.Reference with composition=true, Prop.EntityCollection)
- Element collections: replace-all (delete + re-insert)
- Entity collections: diff-based upsert (preserve child IDs)
- Composing to-one: insert target first, backfill FK

- [ ] **Step 2: Add hydration read support**

For to-many collections on read:
- After main query, execute separate queries for each `Prop.EntityCollection` and `Prop.ElementCollection`
- Group results by parent FK
- Set on the parent entity

Generate hydration methods in `HydrationEmitter`:
- `hydrate(Entity entity)` — umbrella method
- `loadCollectionName(Entity entity)` — per-collection loader

- [ ] **Step 3: Wire hydration into FindEmitter**

After mapping the main query results, call hydration methods for to-many collections.

- [ ] **Step 4: Verify compilation + run integration tests**

```bash
mvn compile -pl processor -q
mvn test -pl integration-tests -q
```

Expected: aggregate tests start passing (ProfileCvTest, AggregateChildSyncTest, etc.).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(processor): add aggregate support — cascade writes and hydration reads"
```

---

### Task 14: @Query Methods + JdbcClient Escape Hatch

**Files:**
- Modify: `processor/src/main/java/io/github/fludakit/data/processor/emitter/FindEmitter.java`
- Modify: `processor/src/main/java/io/github/fludakit/data/processor/RepositoryVisitor.java`

**Interfaces:**
- Consumes: `RepositoryContext`, `MethodContext`
- Produces: `MethodSpec` for `@Query`-annotated methods and JdbcClient accessor methods

- [ ] **Step 1: Add @Query method support to FindEmitter**

For `@Query`-annotated methods:
- Use the raw SQL from the annotation
- Bind parameters by name (matching method parameters)
- Handle return type (SINGLE, LIST, OPTIONAL, etc.)

- [ ] **Step 2: Add JdbcClient escape hatch**

If a repository method returns `JdbcClient`, generate an override that returns the injected `jdbcClient` field.

- [ ] **Step 3: Verify compilation + run integration tests**

```bash
mvn compile -pl processor -q
mvn test -pl integration-tests -q
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(processor): add @Query method support and JdbcClient escape hatch"
```

---

### Task 15: Full Integration Verification

**Files:** none (verification only)

- [ ] **Step 1: Run full integration test suite**

```bash
mvn test -pl integration-tests -q
```

Expected: all 15 test classes pass.

- [ ] **Step 2: Run processor unit tests**

```bash
mvn test -pl processor -q
```

- [ ] **Step 3: Run TCK if present**

```bash
mvn test -pl tck-runner -q
```

- [ ] **Step 4: Verify generated artifact naming**

Check that generated files use the new naming:
- `CustomerRepository_` (not `CustomerRepositoryImpl`)
- `CustomerTypeModel` (not `CustomerMetadata`)
- `CustomerRepositoryQueryModel` (not `CustomerRepositoryQueries`)

- [ ] **Step 5: Commit any fixes**

```bash
git add -A
git commit -m "fix(processor): resolve remaining integration test failures"
```

---

### Task 16: Cleanup + Documentation

**Files:**
- Modify: `processor/src/main/java/io/github/fludakit/data/processor/package-info.java`

- [ ] **Step 1: Update package-info.java**

Update the package documentation to reflect the final structure.

- [ ] **Step 2: Remove any dead code or unused imports**

```bash
mvn compile -pl processor -q -Xlint:all
```

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "chore(processor): cleanup and documentation for visitor-pattern rewrite"
```
