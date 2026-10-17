/**
 * Code generation: writes JavaPoet {@code MethodSpec} and {@code TypeSpec} objects for the six
 * generated artifacts.
 *
 * <p>This package has no compiler-element dependencies beyond what it receives from {@code analysis/}.
 * It reads the compile-time {@code TypeMapping} and emits Java source. One class per generated artifact
 * or per method shape keeps each file focused.</p>
 *
 * <h2>Naming conventions</h2>
 *
 * <p>Three suffixes, each with a precise meaning:</p>
 * <ul>
 *   <li><b>{@code *Generator}</b> — generates a whole <b>type</b> (a {@code .java} file written to
 *       its own {@code TypeSpec}). Examples: {@link TypeMappingGenerator}, {@link RowMapperGenerator},
 *       {@link MetamodelGenerator}.</li>
 *   <li><b>{@code *Emitter}</b> — generates <b>method bodies</b> ({@code MethodSpec}s) attached to
 *       an existing type being built by a generator. Examples: {@link CrudReadEmitter},
 *       {@link WriteMethodEmitter}, {@link PagingEmitter}.</li>
 *   <li><b>{@code *Writer}</b> — writes a file that is neither a generated type nor a method body.
 *       Examples: {@link RepositorySourceWriter} (writes {@code JavaFile} to {@code Filer}),
 *       {@link QueryDefinitionWriter} (writes the Queries holder).</li>
 * </ul>
 *
 * <h2>Orchestration</h2>
 *
 * <ul>
 *   <li>{@link RepositoryImplGenerator} — one {@code @Repository} interface → its {@code <Repository>Impl}
 *       and (when it declares finders) its {@code <Repository>Queries}. The step list: resolve entity,
 *       ensure artifacts, plan loads and cascades, compile methods, write files.</li>
 *   <li>{@link RepositoryMethodCompiler} — compiles one abstract method into its {@code MethodSpec}:
 *       the SQL constant and the {@code jdbcClient} call that executes it. Dispatches ten shapes:
 *       {@code @Query}, CRUD built-ins, writes, and query-by-name/{@code @Find}.</li>
 * </ul>
 *
 * <h2>Generated artifacts</h2>
 *
 * <ul>
 *   <li>{@link RepositoryShellEmitter} — writes the {@code <Repository>Impl} class shell:
 *       {@code @ApplicationScoped}, SQL constants, injected {@code JdbcClient}.</li>
 *   <li>{@link TypeMappingGenerator} — emits {@code <Entity>Metadata}, the reflection-free
 *       {@code TypeMapping} descriptor with column constants and the {@code resolve(path)} switch.</li>
 *   <li>{@link RowMapperGenerator} — emits {@code <Entity>RowMapper}, a reflection-free
 *       {@code mapRow(ResultSet)} with direct {@code rs.getObject} calls.</li>
 *   <li>{@link MetamodelGenerator} — emits the {@code _Entity} static metamodel interface
 *       (Jakarta Data 1.0 §3.3) with {@code UPPER_CASE} attribute constants.</li>
 *   <li>{@link QueryDefinitionWriter} — writes {@code <Repository>Queries}, one nested {@code QueryDefinition}
 *       per query-by-name method.</li>
 * </ul>
 *
 * <h2>Method-shape emitters</h2>
 *
 * <ul>
 *   <li>{@link WriteMethodEmitter} — generates CRUD write methods ({@code @Insert}, {@code @Update},
 *       {@code @Save}, {@code @Delete}), including upsert logic and child cascades.</li>
 *   <li>{@link CrudReadEmitter} — generates inherited CRUD read methods ({@code findById},
 *       {@code findAll}, the paged {@code findAll}).</li>
 *   <li>{@link PagingEmitter} — generates paged read logic (offset and keyset cursor).</li>
 *   <li>{@link HydrationEmitter} — generates the hydration umbrella method and per-collection /
 *       per-relationship loader methods.</li>
 *   <li>{@link JoinedProjectionEmitter} — builds per-method row mappers for reads that JOIN-project
 *       composing to-one targets onto the parent row.</li>
 *   <li>{@link ChildCascadeEmitter} — generates child INSERT/DELETE cascades for aggregate roots.</li>
 * </ul>
 *
 * <h2>Sub-packages</h2>
 *
 * <ul>
 *   <li>{@link io.github.fludakit.data.processor.generator.nav} — navigable member class generation:
 *       the {@code _<Entity><Field>} chain types for embedded, relation, and element-collection
 *       members.</li>
 *   <li>{@link io.github.fludakit.data.processor.generator.metadata} — entity metadata generation:
 *       the {@code <Entity>Metadata} type mapping, the {@code _Entity} metamodel interface, and
 *       the {@code <Repository>Queries} holder.</li>
 *   <li>{@link io.github.fludakit.data.processor.generator.rowmapper} — row mapper generation: the
 *       {@code <Entity>RowMapper} that reads JDBC rows into domain objects and writes them back
 *       to prepared statements.</li>
 * </ul>
 *
 * <h2>Support</h2>
 *
 * <ul>
 *   <li>{@link RepositorySourceWriter} — the only holder of {@code Filer}. Writes {@code JavaFile}
 *       objects to source files and tracks all generated type names for collision detection.</li>
 *   <li>{@link SqlConstants} — manages SQL constant registration and naming
 *       ({@code SQL_FIND_BY_STATUS}, etc.).</li>
 *   <li>{@link QueryInvocations} — shared binding, terminal, and hydration wiring for read methods.</li>
 * </ul>
 */
package io.github.fludakit.data.processor.generator;
