# FluDaKit Workspace — SOLID / OOP Design Review

> **Note:** this review covers the whole fludakit workspace (data, jdbc-client, tx, sql-init, mail, examples). It is committed to the `data` repo as the flagship project; the same file also lives at the workspace root outside any repo. Findings for other repositories are included here so the cross-cutting themes are visible in one place.


**Date:** 2026-10-24
**Scope:** `data`, `jdbc-client`, `tx`, `sql-init`, `mail`, `examples` (all repos at their `main` branch heads)
**Method:** One read-only design review per repository (five run by dedicated review agents, `examples` reviewed directly), focused on SOLID — especially the Single Responsibility Principle — plus OOP design quality (encapsulation, polymorphism over conditionals, composition, ISP, DIP) and classic design smells. Every finding below cites code that was actually read; issues that looked suspicious but turned out fine on inspection were dropped.

---

## 1. Executive summary

The workspace is in **good structural shape for its age**: module layering is consistently right (framework-free `core`, thin `config`/`cdi` adapters depending inward), extension points are real (`MailSender`, `Converter`, `ResourceResolver`, `VersionStrategy`, `PlatformTransactionManager`), and the risky parts are usually backed by tests. This is well above average for early-stage libraries.

The recurring weaknesses are:

1. **The "primary class accumulates everything" pattern** — each library has one class that has quietly become a god class (`JdbcClient` 933 lines / 8 responsibilities; `FindEmitter` 1,507 lines; `RepositoryVisitor` 976 lines; `TransactionalInterceptor` with four jobs).
2. **Duplicated domain logic that has already forked** — two transaction lifecycle state machines (core vs CDI), two cascade-delete implementations (processor), divergent cardinality semantics between two scalar APIs (jdbc-client).
3. **Fail-silent behavior at extension points and misconfiguration** — a lambda `Converter` registration is silently discarded, mail credentials set via config are silently dropped for plain-SE users, an unresolvable `MailConfig` silently falls back to `localhost:25`.
4. **Stringly-typed structure** — placeholder styles, db types, and structural property paths carried as strings with substring/regex dispatch.
5. **Dead or speculative code kept alongside the live design** — a second, unused navigation architecture in `data`, POP3 support in `mail`, a never-read `readOnly` flag in `tx`.

The review also surfaced **seven genuine correctness bugs** (§3) that are small, low-risk fixes and should go first.

---

## 2. Repository verdicts

| Repo | Size (Java files) | Verdict | Headline issue |
|---|---|---|---|
| `data` | ~177 | Solid architecture, one acute hotspot | `FindEmitter` god class + string-surgery SQL assembly; duplicated cascade-delete; dead second navigation design |
| `jdbc-client` | ~20 | Clean layering, one god class | `JdbcClient` holds 8 responsibilities; normalization asymmetry is a real mapping bug |
| `tx` | ~62 | Thoughtful SPI, dangerous seams | Duplicated lifecycle in two front-ends; stale `EntityManager` proxy bug; failure-path leaks |
| `sql-init` | ~51 | Best factored of the smaller libs | Monolithic parser loop; auto-commit contract enforced by callers; triplicated `DbType` aliases |
| `mail` | ~26 | Small and clean | Half-implemented auth path (credentials silently dropped); per-render FreeMarker setup |
| `examples` | ~75 | Healthy | Minor: SendGrid example double-wraps its own exception; S3 resolver lacks pagination |

---

## 3. Correctness bugs found (fix first)

These are behavior bugs, not style findings. All are small, contained fixes.

| # | Repo | Location | Bug |
|---|---|---|---|
| B1 | `tx` | `jpa/src/main/java/io/github/fludakit/tx/jpa/EntityManagerProducer.java:113-129` | `LazyEntityManagerHandler` caches the resolved `EntityManager` for the proxy's lifetime; a `@Dependent` bean injected into an `@ApplicationScoped` client reuses the EM closed by the *first* transaction's `afterCompletion` — the second transaction operates on a closed EM. Also does not unwrap `InvocationTargetException`, so persistence exceptions surface as `UndeclaredThrowableException`. Fix: resolve `JpaHelper.currentEntityManager(emf)` per invocation (cheap map lookup) and rethrow `getCause()`. |
| B2 | `tx` | `jdbc/.../DataSourceTransactionManager.java:40-51`; `jpa/.../JpaTransactionManager.java:69-85` | Failure paths leak resources: if `setAutoCommit(false)` throws, the pooled connection is never closed; in JPA, a throwing `ConnectionExtractor` escapes unmapped *and* the begun `EntityTransaction`/`EntityManager` are never rolled back or closed — despite the manager contract promising cleanup on failure. Fix: try/catch around the risky steps with cleanup-before-rethrow. |
| B3 | `tx` | `cdi/.../TransactionEventReplay.java:68-70`, `TransactionalCdiExtension.java:120-125` | Deferred transaction events are replayed purely by observed *type*; event qualifiers are neither buffered nor matched. An observer declared `@Observes(during = AFTER_SUCCESS) @Admin` receives events fired without `@Admin`. Fix: buffer `(payload, qualifiers)` and match both. |
| B4 | `data` | `processor/.../RepositoryVisitor.java:198, 225` | The `fluda.dialect` processor option only reaches `WriteEmitter`; `FindEmitter` hardcodes `H2Dialect.INSTANCE` (FindEmitter.java:64-70) and `HydrationEmitter` defaults to H2. Latent today (dialects differ only in upsert), but the first dialect divergence in quoting/pagination silently generates wrong finders while saves stay correct. Fix: pass `host.dialect()` to both. |
| B5 | `jdbc-client` | `core/.../JdbcClient.java:797` vs `809, 816-817` | Column keys are normalized with `toLowerCase().replace("_","")` but record components / POJO fields are looked up with `toLowerCase()` only — a component named `dev_name` can never match column `DEV_NAME`, and fields are silently left null with no error. Fix: one shared `normalizeIdentifier` on both sides. |
| B6 | `jdbc-client` | `JdbcClient.java:444-467` vs `694-732` | `optionalValue` re-implements single-row reading and — unlike `query(...).optional()` — does not enforce at-most-one semantics: a multi-row query silently returns the first row through one API and throws `TOO_MANY_RESULTS` through the other. Fix: delegate `optionalValue`/`singleValue` to `QuerySpec`. |
| B7 | `mail` | `core/.../MailConfig.java:22-32, 153-180`; `cdi/.../MailSenderProducer.java:52-68` | `SMTP_USERNAME`/`SMTP_PASSWORD` constants exist but are referenced nowhere; `toJakartaMailProperties()` never writes credentials, and the `Authenticator` wiring exists only in the CDI producer. The README's plain-Java-SE usage (`Session.getInstance(config.toJakartaMailProperties())`) cannot authenticate: it fails at send time. Fix: one core-level `MailSessionFactory.from(MailConfig)` owning properties + authenticator; honor or delete the constants. |

---

## 4. Cross-cutting themes

### T1. The god-class pattern — SRP's most visible violation here

Every library concentrates too many change-axes in its most central class:

- **`jdbc-client`** — `JdbcClient.java` (933 lines) contains the fluent API, SQL parsing (`parseSql`/`ParsedSql`), execution, reflection mapping (`TypedRowMapper`), type conversion (`applyConversions` — a 13-branch type ladder), streaming (`ResultSetSpliterator`), and a Builder — 8 responsibilities. All are private internals, so extraction is behavior-preserving.
- **`data`** — `FindEmitter.java` (1,507 lines): `emitCursorPagedFinder` alone is ~480 lines (605–1086) mixing metadata walking, raw SQL string building, post-hoc `.replace()` surgery on already-rendered SQL (855–905), and Java codegen. The string surgery is temporally coupled to exact rendering of `SeekClause`/`SqlFragment` — any quoting/spacing change makes the replaces silently no-op and produces wrong SQL.
- **`data`** — `RepositoryVisitor.java` (976 lines) does entity resolution, method resolution, result-kind classification, `@Query` regex SQL parsing, and skeleton emission; its parameter validation matches *type strings* (`paramType.startsWith("java.util.List<" + entityType)`, `RepositoryVisitor.java:390-396`) and probes path validity via exceptions (546–558).
- **`tx`** — `TransactionalInterceptor.java` carries propagation dispatch, lifecycle orchestration, rollback-rule policy, and ~60 lines of hand-rolled reflection annotation resolution (159–217) — while `TransactionalCdiExtension.processAnnotatedType` (52–57) already visits every `@Transactional` type at deploy time and throws the resolved metadata away.
- **`sql-init`** — `SqlScriptParser.parse` (57–172) is a 115-line scanner with four loose primitive state variables, juggling quoting, comments, `DELIMITER` directives, and accumulation in one loop.

**Why it matters beyond tidiness:** none of the embedded concerns (SQL parsing, conversion, seek-SQL assembly) can be unit-tested in isolation — which is precisely why the riskiest logic in each library has the weakest direct tests.

### T2. Duplicated domain logic that has already forked

- **`tx`** — `DefaultTransactionalOperations.executeNew/complete/commitTransaction/rollbackTransaction` (85–136) vs `TransactionalInterceptor` (78–131) are near line-identical copies — and have *already diverged*: the programmatic API silently ignores `rollbackOn`/`dontRollbackOn` (rules are interpreted only in the CDI interceptor, so the same definition commits via CDI and rolls back via core), `MANDATORY`/`NEVER` throw different exception types, and `Error` handling differs.
- **`data`** — aggregate cascade-delete exists twice: `FindEmitter.emitDeleteBy` (1151–1225) hand-rolls LOAD_FK/EC_DELETE/CASCADE_DELETE while `WriteEmitter.emitDeleteById` (714–820) drives the same semantics from `MutationPlanAnalyzer` — only the latter respects the analyzer's Rule 4 (skip Aggregate-target collections). The MutationPlan migration simply didn't reach FindEmitter.
- **`data`** — `FindEmitter` re-implements metamodel logic `TypeDefinition` already owns (`getAllProperties` ≡ `properties()`, `computeLocalColumnRefs` ≡ `columnRefs()`, edge-building ≡ `resolve()` internals), with subtle differences (dot-path vs underscore keys) — the classic setup for processor/runtime column-name drift.
- **`sql-init`** — `wildcardRoot` is byte-identical in `ClassPathResourceResolver` (215–229) and `FileSystemResourceResolver` (88–102); jar `URLConnection` boilerplate appears 4×; `DbType` encodes its alias knowledge three independent times (`fromName`, `detectByProductName`, `detectByJdbcUrl`, DbType.java:35–105).
- **`jdbc-client`** — four parameter-binding code paths (single/batch × named/positional) with copy-paste variations (`JdbcClient.java:521-559` vs `593-603`); the batch paths skip the mix-check validation the single paths enforce.

### T3. Fail-silent extension points and fallbacks

- **`jdbc-client`** — `ConverterRegistry.register(lambdaConverter)` returns `this` having registered *nothing* when generics can't be resolved (`ConverterRegistry.java:28-39`); the most natural way to use the SPI is silently discarded, in both manual and CDI discovery paths.
- **`mail`** — no `MailConfig`/`Session` bean → `MailSenderProducer.resolveSession()` silently returns `Session.getInstance(new Properties())` = `localhost:25`, failing only at first send; its javadoc cites a `MailSessionProducer` class that does not exist anywhere.
- **`tx`** — `readOnly` is dead API: `TransactionDefinition.readOnly()` is never read, `executeRead` is behaviorally identical to `execute`, and `triggerBeforeCommit(false)` is hardcoded in both front-ends — users will assume optimizations that never happen.
- **`jdbc-client`** — an unrecognized placeholder string silently falls into positional-mode rewriting (`JdbcClient.java:636-650`); placeholder style is dispatched by substring sniffing (`contains("@")`/`("$")`/`(":")`) and the regex is recompiled on every execution.

### T4. Stringly-typed structure (primitive obsession)

- **`data`/`WriteEmitter`** — structural property paths are carried as concatenated underscore prefixes and *re-parsed by splitting on `_`* (`buildNullGuardedGetter` 960–982, `buildGetterChain` 985–997). A property like `line_1` or `zipCode` re-parses into the wrong getter chain and emits broken generated code.
- **`data`/`RepositoryVisitor`** — generic/type-variable reasoning by string shape (§T1).
- **`sql-init`** — `dbType` is a `String` through core config and validated only at migration time; a typo like `"postgress"` passes `builder().build()`.
- **`mail`** — template naming grammar (`.subject`/`.body`/`.ftl` suffixes) is an undocumented convention assembled across three files (`MailBuilder.java:164-165`, `SimpleTemplateProcessor.java:59`, `TemplateProcessor.java:88-95`).
- **`jdbc-client`** — placeholder configuration is a magic string (`@`/`$n`/`:name`/`?`) instead of an enum.

### T5. Dead / speculative code beside the live design

- **`data`** — an entire second navigation architecture (`QueryAnalyzer`, `NavigationPlan`, `TableAlias`, `LoadStrategy`, plus navPlan-aware overloads in `QueryCompiler:132-148,163-166,221-263` and `QueryPlanner:33,46-61`) has **no production callers** — only its own tests. Meanwhile FindEmitter builds `JoinEdge`s by hand and renders through the non-navPlan path. Either wire it in (which would remove the string surgery in FindEmitter) or delete it; keeping both is the worst option.
- **`mail`** — POP3 support (`protocol="pop3"`, `POP3_*` constants) cannot work through a send-only transport; `Transport.send` ignores `mail.pop3.*` properties.
- **`tx`** — `readOnly` (see T3); core's `TransactionContext.addEvent/getEvents` (267–273) exist solely for the cdi module — CDI concerns leaking into core, while `TransactionScopeContext` demonstrates the better resource-key pattern.
- **`jdbc-client`** — public no-arg constructor + three setters (`JdbcClient.java:91-92, 143-159`) exist only as CDI-proxy accommodations, are called nowhere in any repo, and bypass the null-checks of the real constructor (an uninitialized client NPEs deep in `parseSql`).
- **`jdbc-client`** — `package-info.java` documents `JdbcClient.create(dataSource)`, `updateAndReturnKeys()`, and automatic mapping to `Map` — none of which exist (`Map` targets silently return an effectively empty map).

---

## 5. Per-repository detail

### 5.1 `data` (fluda-data)

**Strengths**
- Single-renderer discipline: `SqlBuilder` renders nothing itself; `QueryCompiler` is the only structured-query renderer; compile-time and runtime SQL cannot drift — documented and enforced (`SqlBuilder.java:31-49`).
- `MutationPlan`/`MutationPlanAnalyzer` is a textbook analysis-vs-emission split (149 lines, 5 documented cascade rules, immutable, unit-tested); `JoinEdge.ToOne/ToMany` encode FK ownership via endpoint *types*, eliminating "which fields do I ignore" conditionals.
- qbn is exemplary: table-driven token→operator switch with explicit refusal defaults (`QbnMethodNameVisitor.java:92-128`).

**Findings** (beyond §3 B4 and §4)
| Finding | Where | Principle | Suggested fix | Effort/Risk |
|---|---|---|---|---|
| `TypeDefinition.resolve()` — 110-line path-resolution algorithm as an interface default method; `Entity`/`Aggregate` records near-identical (8 fields + constructor + `properties()` each); three copy-paste leaf branches | `core/.../metadata/TypeDefinition.java:161-269, 234-255, 287-358` | SRP, duplication | Extract `PathResolver`; collapse Entity/Aggregate; drive leaf branches from a `columnOf(Prop)` accessor | medium/medium |
| `TypeMappingVisitor` — 685-line classifier; `new TypeDefinition.Entity(...)` block copy-pasted 5×; `classifyEntityField` takes 12 parameters (5 output lists) | `processor/.../TypeMappingVisitor.java:191-199, 239-304, 386-396, 586-596` | Duplication, long parameter list | `entityViewOf(Aggregate)` factory + a `PropBag` passed to classifiers | low/low |
| Duplicated emitter infrastructure: `resolveTypeFor` in 3 emitters; `emitTerminal`/`emitTerminalSingle` byte-identical; batch-method detection mirrored inverted in `WriteEmitter` vs `LifecycleEmitter` (drift → method emitted twice or never); three divergent primitive/wrapper type lists; generated-key insert block emitted 3× | `FindEmitter.java:77-82, 1353-1404`; `WriteEmitter.java:55-60, 74-86, 170-449, 1044-1051`; `LifecycleEmitter.java:40-70`; `RowMapperEmitter.java:38-52`; `HydrationEmitter.java:370-378` | DRY | Shared `EmitterSupport` + one `JdbcTypes` utility + one `emitGeneratedKeyInsert(...)` | low/low |
| Underscore-prefix structural paths re-parsed by string splitting | `WriteEmitter.java:960-997, 509-599` | Primitive obsession | Carry `List<String> segments` / a `PropPath` value type | low/medium |

**Test gaps:** `TypeDefinition` (the 481-line centerpiece) has **no direct unit test** — highest-value missing test given the duplication findings; `Sql`/`SqlQuery`/`SqlLifecycle` (~600 lines) untested directly; `SimpleCursoredPage` and `Names.pluralize` edge cases untested; processor emitters asserted only as opaque strings via compile harness (which is why FindEmitter's string-surgery fragility is invisible).

**Quick wins:** (1) wire the dialect (B4, ~5 lines); (2) delete processor-side metamodel copies in favor of `TypeDefinition` accessors; (3) extract `EmitterSupport`.

---

### 5.2 `jdbc-client` (fluda-jdbc-client)

**Strengths**
- Genuinely clean layering: `core` has zero framework deps; `cdi` consumes `config`'s output purely through the core type `JdbcConfig` via `Instance<JdbcConfig>` — DIP done right at the module seam.
- One unchecked exception with a stable `Code` enum; user mappers are pulled into the same contract instead of leaking `SQLException`.
- Real extension point: user `Converter`s are consulted *before* built-in fallbacks and genuinely override defaults.

**Findings** (beyond §3 B5/B6 and §4)
| Finding | Where | Principle | Suggested fix | Effort/Risk |
|---|---|---|---|---|
| `JdbcClient` god class — 8 responsibilities in 933 lines | `core/.../JdbcClient.java:62-933` | SRP | Extract `TypedRowMapper`, `NamedSqlParser`, `ParsedSql`, `QuerySpec` to top-level (all private today — behavior-preserving) | medium/low |
| ~95 lines of built-in conversions as a hard-coded `instanceof` ladder inside the mapper, duplicating the `Converter` SPI's role; "empty registry" still converts, so the registry isn't the source of truth | `JdbcClient.java:837-931` | OCP, conditionals-over-polymorphism | Seed the default registry with built-in `Converter`s; shrink `applyConversions` to lookup + minimal fallback | medium/medium |
| Dead public setters bypass constructor null-checks; no-arg instances NPE deep in `parseSql` | `JdbcClient.java:91-92, 143-159` | Encapsulation | Delete setters; fail fast when uninitialized; consider producing a factory instead of proxyable client | low/low |
| Placeholder style via substring sniffing + per-call `Pattern.compile` | `JdbcClient.java:631, 636-650` | Primitive obsession | `enum PlaceholderStyle` parsed at config boundary; `static final Pattern` | low/low |
| Lambda converter registration silently no-ops | `converter/ConverterRegistry.java:28-39` | Fail-silent API | Throw `IllegalArgumentException` pointing to the explicit overload | low/low |

**Test gaps:** the entire placeholder-rewriting matrix (`$`/`:`/`@`) is never executed against a database; no test registers a custom `Converter` and asserts it's applied (the SPI's whole purpose); `ConverterRegistry`/`KeyHolder` have no direct tests; the cdi↔config seam (including the silent `JdbcConfig.DEFAULT` fallback on ambiguous resolution) is untested.

**Quick wins:** (1) delete dead setters + fail fast; (2) `static final` regex + enum placeholder; (3) fix `package-info.java` (documents three APIs that don't exist) + two cheap tests (custom `Converter` applied; `$`-placeholder query against H2).

---

### 5.3 `tx` (fluda-tx)

**Strengths**
- Genuinely narrow SPI: `PlatformTransactionManager` is 3 methods and deliberately does not interpret propagation — adapters stay ~100 lines (OCP/DIP done right).
- `TransactionContext` resource keying uses `IdentityHashMap` with documented CDI-proxy rationale; multi-release holder with JDK 25 `ScopedValue` + documented ITL fallback, and structured-concurrency inheritance is actually asserted in a test.
- Real integration tests: H2 + HikariCP + Hibernate + Weld, not mocks.

**Findings** (beyond §3 B1–B3 and §4)
| Finding | Where | Principle | Suggested fix | Effort/Risk |
|---|---|---|---|---|
| Two parallel lifecycle state machines, already forked (rollback rules, exception types, `Error` handling) | `core/.../DefaultTransactionalOperations.java:85-136` vs `cdi/.../TransactionalInterceptor.java:78-152` | DRY / single source of truth | Core `TransactionExecutionTemplate` (begin→bind→execute→complete) with pluggable rollback strategy + callback-registrar hook; both front-ends delegate. Collapses findings T2 + the rollback-rule divergence + most of the interceptor's SRP problem in one move | medium/medium |
| `TransactionContext` leaks live internals; built-in managers bypass the public `bindResource`/`registerCallback` via `getResources().put(...)`; `NON_TRANSACTIONAL` shared mutable singleton — any mutation during a `NOT_SUPPORTED` scope poisons all subsequent executions on all threads | `core/.../TransactionContext.java:141, 235-241`; `jdbc/.../DataSourceTransactionManager.java:45-46` | Encapsulation | Unmodifiable views; managers use public API; immutable (throwing) sentinel or fresh instance per suspension | low/low |
| ITL fallback staleness: a thread created mid-transaction inherits a *completed* context forever (`isBound()` stays true, closed resources handed out); base `TransactionContext` collections not concurrent-safe despite cross-thread use being advertised | `core/src/main/java25/.../TransactionContextHolder.java:14-50`; `core/.../TransactionContext.java:144-148` | Thread-safety / coupling | Reject completed contexts in proxy/scope; concurrent collections; document or drop the fallback | high/high (product decision) |
| CDI event buffering lives in core (`addEvent`/`getEvents`) while `TransactionScopeContext` shows the better resource-key pattern; `EntityManagerProducer` (a CDI bean) lives in `jpa` while `TransactionalOperationsProducer` lives in `cdi` | `core/.../TransactionContext.java:267-273`; `jpa/.../EntityManagerProducer.java` | ISP / module boundaries | Move buffering behind a cdi-owned resource; relocate the producer to `cdi` or a `jpa-cdi` artifact | medium/low |

**Test gaps:** no `ConnectionExtractor` test at all; `readOnly` never asserted; no qualified-event test (hides B3); no test invoking an injected `EntityManager` across two sequential transactions (hides B1); no failure-path tests (hides B2).

**Quick wins:** (1) fix `LazyEntityManagerHandler` (B1, ~10 lines); (2) leak-free failure paths (B2, two small try/catch blocks); (3) wire or delete `readOnly`.

---

### 5.4 `sql-init` (fluda-sql-init)

**Strengths**
- Best-factored small library in the workspace: parsing, resolution, execution, and history are genuinely separate; `VersionStrategy` SPI with three implementations is a textbook strategy; `ResourceResolverRegistry` doubling as a composite resolver is a neat small pattern.
- Atomic claiming + retry semantics (insert-`running`-row-then-execute; leftover rows re-runnable) are well thought out and tested.
- Immutable config with correct null-hardening in the builder.

**Findings**
| Finding | Where | Principle | Suggested fix | Effort/Risk |
|---|---|---|---|---|
| 115-line monolithic scanner, primitive state | `core/.../SqlScriptParser.java:57-172` | SRP / long method | `ScannerState` + extracted handlers; public static API unchanged; 17 parser tests de-risk it | medium/low |
| `runMigration` mixes transaction-mode plumbing (documented as the *caller's* contract on `MigrationHistory`) with lifecycle; two near-identical catch arms; message double-wrapped | `core/.../DbMigrator.java:137-172, 160, 193` | SRP / duplication | Small `MigrationTransaction` wrapper doing rollback + autocommit restore; collapse catch arms; wrap message once | medium/medium |
| `dbType` a String through core; validated only mid-`migrate()` | `SqlInitConfig.java:55-58, 82-84` | Primitive obsession | `Builder.dbType(DbType)`; validate in `build()` | low/low |
| Version-strategy name mapping + reflection instantiation lives in the `config` submodule; MP-Config defaults re-hardcode core defaults | `config/.../SqlInitConfigProducer.java:39-64`; `SqlInitProperties.java:16-26` | SRP / DRY across modules | `VersionStrategy.fromName(String)` in core; reference `SqlInitConfig.DEFAULT_*` constants | low/low |
| `ClassPathResourceResolver` mixes four scanning strategies with WildFly VFS inlined (largest class, 230 lines; VFS branch untestable) | `core/.../resource/ClassPathResourceResolver.java:110-169` | SRP | Extract package-private `VfsScanner`, loaded reflectively on `vfs:` protocol | medium/low |
| `DbType` alias knowledge encoded 3× | `DbType.java:35-105` | Shotgun change | Give each constant its aliases/URL-prefixes as data; implement lookups as loops | low/low |
| Public `package-info.java` promises `historyTable(...)` and table `schema_migrations` — neither exists (actual: `db_migrations`) | `core/.../sqlinit/package-info.java:18-56` | Misleading public docs | Fix the snippet | trivial |

**Test gaps:** VFS branch (needs WildFly — worth an `@EnabledIfClassPresent` note); no direct `UrlResource` test; no end-to-end custom `separator`/explicit `dbType` test; `DbMigratorTest` hand-creates `db_migrations` with `version INT PRIMARY KEY` while every shipped dialect DDL uses `VARCHAR(100)` — retry paths never run against the real schema shape.

**Quick wins:** (1) fix `package-info.java`; (2) de-duplicate `wildcardRoot` + jar-connection boilerplate; (3) `Builder.dbType(DbType)` + `VersionStrategy.fromName(String)`.

---

### 5.5 `mail` (fluda-mail)

**Strengths**
- Genuinely decoupled: `cdi` consumes `config` only via `Instance<MailConfig>`; module-infos confirm no reverse require.
- Minimal mockable SPIs (`MailSender` one method; `TemplateProcessor` functional), proven by a CDI `@Decorator` test; honest optional FreeMarker handling (`requires static freemarker`).
- Real end-to-end verification against a Mailpit container, not just mocks.

**Findings** (beyond §3 B7 and §4)
| Finding | Where | Principle | Suggested fix | Effort/Risk |
|---|---|---|---|---|
| `TemplateProcessor.DEFAULT` runs `Class.forName` sniffing and builds a fresh FreeMarker `Configuration` (fresh template cache) on **every render**; impl nested inside the SPI interface | `core/.../template/TemplateProcessor.java:37-42, 75-85` | SRP + perf | Top-level `FreeMarkerTemplateProcessor` with lazy-holder `Configuration`; cache the chosen processor | low/low |
| POP3 branch unreachable through send-only transport | `MailConfig.java:44-45, 168-174` | YAGNI | Remove until a receiving API exists | low/low |
| `MailMessage` leaks live collections; builder mutates through them | `MailMessage.java:26-28, 50-52`; `MailBuilder.java:129-132` | Encapsulation | `addAttachment(...)` + unmodifiable views | low/low |
| Template naming grammar split across three files, undocumented on the interface | `MailBuilder.java:164-165`; `SimpleTemplateProcessor.java:59`; `TemplateProcessor.java:88-95` | Hidden convention | Declare the suffix contract + constants on `TemplateProcessor`, or a `TemplateRef` value type | medium/medium |
| Test fixtures duplicated core↔cdi (`MailAssertions`, `MailTrackingRegistry` near byte-identical) | `core/src/test/...` vs `cdi/src/test/...` | Duplication | test-jar or test-fixtures module | low/low |
| Stale docs/version drift: `StringTemplateProcessor` in examples, `mail.*` vs `fluda.mail.*` prefix, FreeMarker `VERSION_2_3_32` hardcoded vs 2.3.35 managed | `SimpleTemplateProcessor.java:25`; `MailConfigProducer.java:12`; `TemplateProcessor.java:76` | Docs hygiene | Trivial fixes | trivial |

**Test gaps:** attachments are tested **nowhere** (the whole multipart branch of `JakartaMailSender` has zero coverage); `MailSenderProducer`'s session-resolution priority never tested; `MailBuilder.template()` untested; `toJakartaMailProperties()` branches untested; authenticated SMTP never tested end-to-end.

**Quick wins:** (1) credential fix via a core `MailSessionFactory` (B7); (2) cache FreeMarker setup; (3) fail loud on the empty-config fallback + delete the phantom `MailSessionProducer` javadoc reference.

---

### 5.6 `examples` (fluda-examples)

**Verdict:** healthy. Try-with-resources everywhere, one scenario per feature, per-platform `DataSourceProducer` variants are *correctly* different (EE `@DataSourceDefinition` vs SE Hikari + `TransactionAwareDataSourceProxy`), and the root pom centralizes all fluda versions in `dependencyManagement`. Repository examples are idiomatic and small (`CustomerRepository` is 46 lines including docs).

**Findings**
| Finding | Where | Fix | Effort/Risk |
|---|---|---|---|
| `SendGridMailSender` double-wraps its own `MailException`: the `catch (Exception)` at line 78 re-wraps the "SendGrid rejected payload: 4xx" thrown at 76, burying the status code; also calls `attachment.toDataSource()` twice (52, 58) building two DataSources | `mail-sendgrid/src/main/java/com/example/SendGridMailSender.java:51-80` | Catch `MailException` separately / rethrow; assign the DataSource once | low/low |
| `SqlInitStandaloneExample` never closes the `S3Client` (AutoCloseable) — it is the canonical custom-resolver example that users will copy | `sql-init/src/main/java/com/example/SqlInitStandaloneExample.java:38-52` | try-with-resources around the client | trivial |
| `S3ResourceResolver.getResources` doesn't paginate `ListObjectsV2` (>1000 keys silently truncated) and has no continuation-token loop — fine for a demo, dangerous if copied to production | `sql-init/src/main/java/com/example/S3ResourceResolver.java:47-65` | Add a comment, or paginate | low/low |
| `Engineer.java` byte-identical across javase/servlet/vanilla | 3 modules | Acceptable for self-contained examples — leave as-is, or note the convention in the README | — |

---

## 6. Suggested PR roadmap

Ordered by value/risk ratio. All are scoped to be individually reviewable; none require touching public API semantics except where noted (all libraries are `1.0.0-SNAPSHOT`, so no published-API constraints).

**Wave 1 — bug fixes (small, safe, high value)**
1. `tx`: `LazyEntityManagerHandler` per-invocation resolution + `InvocationTargetException` unwrap (B1).
2. `tx`: leak-free failure paths in both transaction managers (B2).
3. `data`: pass `host.dialect()` to `FindEmitter`/`HydrationEmitter` (B4).
4. `jdbc-client`: shared `normalizeIdentifier` (B5); delegate `optionalValue`/`singleValue` to `QuerySpec` (B6).
5. `mail`: core `MailSessionFactory` owning properties + authenticator (B7).
6. `examples`: SendGrid exception handling; close `S3Client`.

**Wave 2 — fail-loud + dead-code cleanup**
7. `jdbc-client`: delete dead setters, fail fast uninitialized; throw on unresolvable lambda `Converter` registration; `static final` regex + `PlaceholderStyle` enum.
8. `tx`: wire-or-delete `readOnly`; unmodifiable `TransactionContext` views + immutable `NON_TRANSACTIONAL` sentinel.
9. `mail`: loud fallback in `MailSenderProducer`; delete POP3 branch; cache FreeMarker.
10. `data`: decide NavigationPlan — wire into FindEmitter or delete the parallel architecture.
11. `sql-init`: fix `package-info.java`; `jdbc-client`: fix `package-info.java`.

**Wave 3 — structural refactors (each preceded by characterization tests)**
12. `tx`: `TransactionExecutionTemplate` unifying the two lifecycle state machines (fixes the rollback-rule divergence by construction).
13. `jdbc-client`: split `JdbcClient` into `NamedSqlParser` / `TypedRowMapper` / `QuerySpec`; seed default registry with built-in `Converter`s.
14. `data`: route `deleteById` through `MutationPlanAnalyzer` (single cascade implementation); extract `EmitterSupport` + `JdbcTypes`; replace underscore-prefix paths with `PropPath`.
15. `sql-init`: extract parser `ScannerState`/handlers; `MigrationTransaction` wrapper.
16. `data`: `PathResolver` extraction + Entity/Aggregate collapse in `TypeDefinition` — after adding the currently missing direct unit tests for it.

**Test debt worth paying regardless of refactors:** direct tests for `TypeDefinition` (data); placeholder-style matrix against H2 + custom-`Converter`-applied test (jdbc-client); two-sequential-transaction EM injection + qualified-event tests (tx); attachment-path tests (mail).

---

*Generated by a read-only design review on 2026-10-24. Line numbers refer to repository heads at review time.*
