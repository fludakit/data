/**
 * Compile-time analysis: reads entity fields, resolves generic types, validates methods, and plans
 * loads and cascades.
 *
 * <p>This package produces the {@link io.github.fludakit.data.metadata.TypeMapping TypeMapping} every
 * generator consumes. It writes no source files — it only reads compiler elements and answers
 * structural questions about the entity model.</p>
 *
 * <h2>Key classes</h2>
 *
 * <ul>
 *   <li>{@link io.github.fludakit.data.processor.analysis.TypeModelBuilder} — builds and memoizes
 *       the compile-time {@code TypeMapping} for entities and value objects. Scans fields, classifies
 *       them (id, scalar, reference, embedded, entity collection, element collection). The central
 *       model builder.</li>
 *   <li>{@link io.github.fludakit.data.processor.generator.ArtifactCoordinator} — resolves the
 *       generated artifacts (metamodel, metadata, row mapper) for a model, idempotently. It lives in
 *       {@code generator} so this package reads javac and produces the model without depending on any
 *       generator.</li>
 *   <li>{@link io.github.fludakit.data.processor.analysis.RepositoryTypeResolver} — resolves the entity
 *       type from a repository's generic supertype hierarchy ({@code DataRepository<T,ID>}, etc.).</li>
 *   <li>{@link io.github.fludakit.data.processor.analysis.RepositoryMethodValidator} — checks every
 *       abstract repository method before code generation.</li>
 *   <li>{@link io.github.fludakit.data.processor.analysis.QueryPlanBuilder} — compiles one method
 *       declaration into a {@code QueryPlan}: parsed name, predicates, sorts, seek terms, parameter
 *       bindings.</li>
 *   <li>{@link io.github.fludakit.data.processor.analysis.PathResolver} — resolves logical attribute
 *       paths to physical {@code ColumnRef}s with join information.</li>
 *   <li>{@link io.github.fludakit.data.processor.analysis.LoadPlanner} — plans which
 *       relationships need runtime hydration (loader queries) vs JOIN projection.</li>
 *   <li>{@link io.github.fludakit.data.processor.analysis.SavePlanner} — plans write cascades
 *       for aggregate children.</li>
 * </ul>
 *
 * <p>{@code AttributeTypes} wraps compiler {@code Elements}/{@code Types} for the metamodel-kind and
 * enum questions; {@link io.github.fludakit.data.processor.analysis.TypeMirrors} answers the shared
 * type questions (sequence element, is-this-the-entity) as static methods over a raw pair, so every
 * other class holds {@code Types}/{@code Elements} directly.</p>
 */
package io.github.fludakit.data.processor.analysis;
