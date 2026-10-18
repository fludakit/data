# Processor Visitor Rewrite Design

## Overview

Rewrite the annotation processor module from scratch on a new branch (`refactor/processor-visitor-rewrite`), following Micronaut Data 5.2.x's visitor-pattern architecture. The previous branch (`refactor/processor-monolithic-redesign`) serves as reference only.

**Goal:** A clean processor implementation using visitors for type scanning and repository processing, with separate contexts and emitters, producing the same features as the existing processor.

**Scope:** Processor module rewrite + minor core tweaks (generated naming only). Core interfaces (`TypeMapping`, `ColumnResolver`, `SqlBuilder`, `ColumnRef`, `ColumnPlacement`) stay unchanged.

## Architecture

Three-phase pipeline driven by visitors:

```
APT Round
  |
  +- Phase 1: Type Scanning
  |   TypeMappingVisitor scans @Entity, @ValueObject, @AggregateRoot, @Identity
  |   -> builds TypeMapping hierarchy, caches by FQN, handles cycles
  |
  +- Phase 2: Repository Visiting
  |   RepositoryVisitor visits each @Repository interface
  |   +- resolves entity type -> fetches TypeMapping from Phase 1 cache
  |   +- creates RepositoryContext (per-repository state)
  |   +- iterates abstract methods -> creates MethodContext per method
  |   |   +- resolves predicates (via entity.resolve(path))
  |   |   +- resolves sorts (via entity.resolve(path))
  |   |   +- determines operation kind, result kind, distinct
  |   +- delegates to emitters with RepositoryContext + MethodContext
  |
  +- Phase 3: Artifact Writing
      RepositoryVisitor writes: Repository_, row mapper, _Entity metamodel,
      XXXTypeModel, RepositoryQueryModel
```

**Key principle:** Visitors analyze and accumulate state into contexts. Emitters consume contexts to produce JavaPoet specs. No emitter reads annotation processing APIs directly.

## Package Structure

```
processor/src/main/java/io/github/fludakit/data/processor/
  RepositoryProcessor.java          -- APT entry point (thin orchestrator)
  visitor/
    TypeMappingVisitor.java         -- Phase 1: type scanning + TypeMapping cache
    RepositoryVisitor.java          -- Phase 2: per-repository processing
  context/
    RepositoryContext.java          -- per-repository accumulated state
    MethodContext.java              -- per-method resolved state
  emitter/
    FindEmitter.java                -- read queries (plain, paged, cursored, count, exists)
    WriteEmitter.java               -- save, insert, update, delete, upsert
    LifecycleEmitter.java           -- batch lifecycle operations
    RowMapperEmitter.java           -- row mapper generation
    MetamodelEmitter.java           -- _Entity attribute classes
    TypeModelEmitter.java           -- XXXTypeModel runtime TypeMapping impl
    QueryModelEmitter.java          -- RepositoryQueryModel static query definitions
  support/
    Diagnostics.java                -- error reporting
    MemberNames.java                -- naming conventions
    Props.java                      -- property utilities
    MethodResults.java              -- result kind resolution
    GeneratedKeyCoercion.java       -- generated key handling
    JoinEdges.java                  -- join edge utilities
    QueryInvocations.java           -- query invocation helpers
```

## Components

### RepositoryProcessor

The APT entry point. Thin orchestrator — owns lifecycle, delegates everything.

```java
@SupportedAnnotationTypes({"jakarta.data.repository.Repository",
                            "io.github.fludakit.data.annotation.Entity",
                            "io.github.fludakit.data.annotation.ValueObject",
                            "io.github.fludakit.data.annotation.AggregateRoot"})
@SupportedOptions("fluda.dialect")
public class RepositoryProcessor extends AbstractProcessor {

    private TypeMappingVisitor typeMappingVisitor;
    private SqlDialect dialect;
    private Filer filer;
    private Diagnostics diagnostics;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // Phase 1: Scan types
        typeMappingVisitor.scanRound(roundEnv);

        // Phase 2: Visit repositories
        for (Element element : roundEnv.getElementsAnnotatedWith(Repository.class)) {
            TypeElement repository = (TypeElement) element;
            new RepositoryVisitor(repository, this).visit();
        }

        return false;
    }

    // Package-private accessors for visitors/emitters
    TypeMappingVisitor typeMappingVisitor() { return typeMappingVisitor; }
    SqlDialect dialect() { return dialect; }
    Filer filer() { return filer; }
    Diagnostics diagnostics() { return diagnostics; }
}
```

RepositoryProcessor does NOT do: method resolution, SQL generation, code emission, type scanning logic.

### TypeMappingVisitor

Scans annotated types and builds the `TypeMapping` hierarchy. Owns the cache.

```java
final class TypeMappingVisitor {
    TypeMappingVisitor(Types types, Elements elements, Diagnostics diagnostics);

    void scanRound(RoundEnvironment roundEnv);
    TypeMapping<?> visitType(TypeElement type);
    TypeMapping<?> get(String fqn);
    Set<String> managedEntities();
}
```

**`visitType(TypeElement)` flow:**
1. Check cache -> return if present
2. Check in-progress -> return placeholder if cyclic
3. Mark in-progress
4. Determine shape: `@AggregateRoot`? `@Entity`? `@ValueObject` field? `@ValueObject` collection?
5. For each field: classify via `Prop` variant, resolve column/target recursively
6. Build the `TypeMapping` record
7. Cache and return

### RepositoryVisitor

Processes one `@Repository` interface.

```java
final class RepositoryVisitor {
    RepositoryVisitor(TypeElement repository, RepositoryProcessor host);
    void visit();
}
```

**`visit()` flow:**
1. Resolve entity type from generic parameter
2. Fetch `TypeMapping` from TypeMappingVisitor cache
3. Determine repository flavour: PLAIN, DATA_REPOSITORY, CRUD_REPOSITORY
4. Create RepositoryContext
5. Pre-resolve phase: iterate abstract methods -> create MethodContext per method
6. Emission phase: delegate to emitters per method
7. Auxiliary generation: joined projection mappers, hydration methods
8. Write phase: emit Repository_, row mapper, metamodel, TypeModel, QueryModel

**Method dispatch:**
```
for each abstract method:
  MethodContext ctx = resolveMethod(method, repositoryType, entity)
  switch (ctx.operation()):
    FIND/COUNT/EXISTS/DELETE_BY -> FindEmitter
    SAVE/INSERT/UPDATE/DELETE    -> WriteEmitter
    batch lifecycle              -> LifecycleEmitter
```

### RepositoryContext

Per-repository accumulated state. Emitters read it; they never re-derive facts.

```java
public final class RepositoryContext {
    // Identity (fixed at construction)
    private final TypeMapping<?> entity;
    private final boolean crud;
    private final boolean spec;
    private final ClassName rowMapperType;
    private final String repositoryFqn;

    // Emission state (mutable, appended by emitters)
    private final Map<String, String> constants = new LinkedHashMap<>();
    private final List<MethodSpec> auxMethods = new ArrayList<>();
    private final Map<String, Integer> queryDefinitionNames = new HashMap<>();
}
```

Relationships are accessed on demand via `entity.props()` filtered by Prop variant — no separate relationship lists.

### MethodContext

Per-method resolved state. Created by RepositoryVisitor, consumed by emitters.

```java
public final class MethodContext {
    private final ExecutableElement method;
    private final String name;
    private final Operation operation;
    private final ResultKind resultKind;
    private final boolean distinct;
    private final List<? extends VariableElement> parameters;
    private final List<ParamBinding> paramBindings;

    // Predicates and sorts carry their own paths;
    // ColumnPlacement resolved on demand via entity.resolve(path)
    private final List<Predicate> predicates;
    private final List<SortSpec> sorts;

    private final PagingMode pagingMode;
    private final String customSql;
}
```

No separate ResolvedPredicate/ResolvedSort/ResolvedPath records. The `Predicate` and `SortSpec` carry their attribute paths. Emitters call `entity.resolve(path)` to get `ColumnPlacement` when needed.

### Emitters

Stateless. Constructed once, reused across all repositories. Each follows the same contract:

```java
final class FindEmitter {
    FindEmitter(SqlDialect dialect);
    MethodSpec emit(RepositoryContext ctx, MethodContext method);
}
```

| Emitter | Responsibility |
|---|---|
| FindEmitter | Read queries: plain, paged, cursored, count, exists |
| WriteEmitter | Save, insert, update, delete, upsert |
| LifecycleEmitter | Batch lifecycle operations |
| RowMapperEmitter | Row mapper class generation |
| MetamodelEmitter | `_Entity` attribute classes |
| TypeModelEmitter | `XXXTypeModel` runtime TypeMapping implementation |
| QueryModelEmitter | `RepositoryQueryModel` static query definitions |

Emitters use `SqlBuilder` with the entity's `TypeMapping` to render SQL. `SqlBuilder` calls `entity.resolve(path)` internally.

## Property Model

| Semantic | Annotation/Shape | Prop variant | Storage |
|---|---|---|---|
| Identifier | `@Identity` | `Prop.Id` | column on owner's table |
| Scalar | primitive/String/enum | `Prop.Scalar` | column on owner's table |
| Value Object | `@ValueObject` field | `Prop.Embedded` | columns flattened into owner's table |
| VO Collection | `List<@ValueObject>` | `Prop.ElementCollection` | child table, parent FK |
| Association | `@Association` field | `Prop.Reference` (composition=false) | FK column on owner's table |
| Composition | non-lazy child entity | `Prop.Reference` (composition=true) | FK column, eagerly loaded, cascaded |
| Entity Collection | `List<@Entity>` | `Prop.EntityCollection` | child table, inverse FK |

The Prop sealed hierarchy uses physical storage terms (Embedded/ElementCollection/Reference). The semantic annotations (`@ValueObject`, `@Association`, `@Identity`) map to these during type scanning.

## Generated Artifacts

| Artifact | Naming | Purpose |
|---|---|---|
| Metamodel | `_EntityName` | Attribute constants and typed attribute instances for path navigation |
| TypeModel | `EntityNameTypeModel` | Runtime `TypeMapping` implementation, path-to-column resolution for `SqlBuilder` |
| Repository impl | `RepositoryName_` | Generated repository implementation class |
| Query model | `RepositoryNameQueryModel` | Static `QueryDefinition` fields for published queries |

The generated `XXXTypeModel` is the serialized form of the compile-time `TypeMapping`, written to source so `SqlBuilder` can use it at runtime.

## Path Resolution

Paths are always rooted at the `TypeMapping` being queried. The same physical column can be reached by different paths from different roots:

- `Order` standalone: `customer.name` -> `ColumnRef(name, customer_table)`
- Aggregate root: `order.customer.name` -> same `ColumnRef(name, customer_table)`

`ColumnRef` (column + table) is unique. `ColumnPlacement` (ColumnRef + join edges) tells the emitter how to reach it in SQL. The path is an input to resolution (from Predicate/SortSpec), not an output. No need to trace back from ColumnPlacement to path.

## Core Tweaks

1. **Generated naming convention** — processor generates `XXXTypeModel` (not `XXXMetadata`), `Repository_` (not `RepositoryImpl`), `RepositoryQueryModel` (not `RepositoryQueries`). Processor-side change only.

2. **No changes to core interfaces** — `TypeMapping`, `ColumnResolver`, `SqlBuilder`, `ColumnRef`, `ColumnPlacement`, `JoinEdge` stay unchanged.

## Design Decisions

- **Visitor pattern over monolithic** — follows Micronaut Data's structure for extensibility
- **Unified TypeMappingVisitor** — one visitor handles all TypeMapping variants via the sealed hierarchy, not separate visitors per type kind
- **Separate emitters** — visitors analyze, emitters generate. Clear separation of concerns
- **No intermediate resolution records** — predicates/sorts carry their own paths; ColumnPlacement resolved on demand via TypeMapping.resolve()
- **No EntityRelationship carrier** — relationships accessed through entity.props() filtered by Prop variant
- **RepositoryContext is lean** — no pre-computed relationship lists; derives from entity model on demand
- **MethodContext is lean** — no Object parsed field; QBN results decomposed into predicates/sorts during resolution

## Reference

- Previous branch: `refactor/processor-monolithic-redesign` (reference only, not to be merged)
- Micronaut Data 5.2.x processor structure: visitors/, model/, mappers/
- DRAFT.MD: Jakarta Data Processor design notes
- Jakarta Data 1.0 specification
