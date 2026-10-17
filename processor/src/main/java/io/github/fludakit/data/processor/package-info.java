/**
 * Compile-time annotation processor for Jakarta Data 1.0 repositories.
 *
 * <h2>Pipeline overview</h2>
 *
 * <p>The processor transforms {@code @Repository} interfaces into working implementations through
 * five stages. Each stage is a package or a class; the flow is strictly top-down.</p>
 *
 * <pre>{@code
 * ┌─────────────────────────────────────────────────────────────────────────────────────┐
 * │  INPUT: @Repository interface + entity classes                                      │
 * └────────────────────────────────────┬────────────────────────────────────────────────┘
 *                                      ▼
 * ┌─────────────────────────────────────────────────────────────────────────────────────┐
 * │  1. RESOLVE  (analysis/)                                                            │
 * │     RepositoryTypeResolver → entity type T from DataRepository<T,ID>                │
 * │     TypeModelBuilder → compile-time TypeMapping (fields → columns, relations)      │
 * └────────────────────────────────────┬────────────────────────────────────────────────┘
 *                                      ▼
 * ┌─────────────────────────────────────────────────────────────────────────────────────┐
 * │  2. GENERATE METADATA  (generator/)                                                │
 * │     TypeMappingGenerator → <Entity>Metadata (reflection-free TypeMapping impl)         │
 * │     RowMapperGenerator → <Entity>RowMapper (reflection-free ResultSet reader)       │
 * │     MetamodelGenerator → _Entity static metamodel + navigable member classes        │
 * └────────────────────────────────────┬────────────────────────────────────────────────┘
 *                                      ▼
 * ┌─────────────────────────────────────────────────────────────────────────────────────┐
 * │  3. PLAN  (analysis/)                                                               │
 * │     LoadPlanner → which relations need hydration loaders vs JOIN        │
 * │     SavePlanner → which children participate in aggregate writes             │
 * └────────────────────────────────────┬────────────────────────────────────────────────┘
 *                                      ▼
 * ┌─────────────────────────────────────────────────────────────────────────────────────┐
 * │  4. COMPILE METHODS  (generator/)                                                   │
 * │     RepositoryMethodValidator → reject unsupported shapes                           │
 * │     RepositoryMethodCompiler → one MethodSpec per abstract method:                  │
 * │       • @Query → native SQL, bind parameters                                        │
 * │       • CRUD built-ins → findById, findAll, save, insert, update, delete            │
 * │       • @Find / query-by-name → parse name, resolve paths, render SQL               │
 * │       • @Insert/@Update/@Save/@Delete → lifecycle statements with cascades          │
 * └────────────────────────────────────┬────────────────────────────────────────────────┘
 *                                      ▼
 * ┌─────────────────────────────────────────────────────────────────────────────────────┐
 * │  5. WRITE  (generator/)                                                             │
 * │     RepositoryShellEmitter → <Repository>Impl (@ApplicationScoped, JdbcClient)      │
 * │     QueryDefinitionWriter → <Repository>Queries (QueryDefinition per query-by-name method)  │
 * └────────────────────────────────────┬────────────────────────────────────────────────┘
 *                                      ▼
 * ┌─────────────────────────────────────────────────────────────────────────────────────┐
 * │  OUTPUT: 6 generated artifacts per repository                                       │
 * │    1. <Repository>Impl        — CDI bean implementing the interface                 │
 * │    2. <Entity>Metadata        — reflection-free TypeMapping descriptor              │
 * │    3. <Entity>RowMapper       — reflection-free ResultSet → entity                  │
 * │    4. _Entity                 — Jakarta Data static metamodel interface             │
 * │    5. _<Entity><Field>        — navigable member classes (per embedded/relation)    │
 * │    6. <Repository>Queries     — QueryDefinition holders (when finders exist)        │
 * └─────────────────────────────────────────────────────────────────────────────────────┘
 * }</pre>
 *
 * <h2>Packages</h2>
 *
 * <ul>
 *   <li><strong>this package</strong> — entry point ({@link RepositoryProcessor}) and round
 *       assembly ({@link Round}). No logic here; just wiring.</li>
 *   <li><strong>{@code analysis/}</strong> — compile-time model building. Reads entity fields,
 *       resolves generic types, validates methods, plans loads and cascades. Produces the
 *       {@code TypeMapping} every generator consumes.</li>
 *   <li><strong>{@code generator/}</strong> — code generation. Writes JavaPoet {@code MethodSpec} and
 *       {@code TypeSpec} objects. One class per generated artifact or per method shape.</li>
 *   <li><strong>{@code model/}</strong> — internal data structures. Records and value objects that
 *       flow between analysis and emit. Not exposed outside the processor.</li>
 *   <li><strong>{@code support/}</strong> — infrastructure. Diagnostics and naming/key-coercion
 *       constants. The only package that names {@code Messager} or {@code Filer}.</li>
 * </ul>
 *
 * <h2>Key concepts</h2>
 *
 * <p><strong>Idempotent generation.</strong> A repository's entity may be reached from multiple
 * repositories in the same round (e.g., {@code OrderRepository} and {@code ItemRepository} both
 * reference {@code Customer}). The first repository to need {@code _Customer}, {@code CustomerMetadata},
 * or {@code CustomerRowMapper} triggers generation; later requests hit the cache in
 * {@link io.github.fludakit.data.processor.GeneratedArtifacts}. See
 * {@link io.github.fludakit.data.processor.generator.ArtifactCoordinator} for the protocol.</p>
 *
 * <p><strong>Session-scoped vs round-scoped.</strong> Two caches outlive a round:
 * {@code GeneratedArtifacts} (what has been emitted or claimed) and
 * {@link io.github.fludakit.data.processor.analysis.TypeModelBuilder} (the descriptor memo that
 * prevents a value object reached from two owners from being analyzed twice). Everything else is
 * rebuilt per round; see {@link Round} for the construction order.</p>
 *
 * <p><strong>SQL rendering.</strong> All SQL flows through {@code SqlBuilder} and the configured
 * {@code SqlDialect} (H2 or PostgreSQL). The processor bakes the rendered SQL into the generated
 * implementation as {@code static final String} constants; there is no runtime query model.</p>
 *
 * <h2>Further reading</h2>
 *
 * <ul>
 *   <li>{@code DRAFT.MD} — design notes explaining the <em>why</em> behind each decision</li>
 *   <li>{@code README.md} — project overview, generated artifacts, query-by-method-name conformance</li>
 * </ul>
 *
 * @see RepositoryProcessor
 * @see Round
 * @see io.github.fludakit.data.processor.analysis
 * @see io.github.fludakit.data.processor.generator
 */
package io.github.fludakit.data.processor;
