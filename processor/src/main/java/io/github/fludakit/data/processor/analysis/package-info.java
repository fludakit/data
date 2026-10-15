/**
 * Compile-time analysis: reads entity fields, resolves generic types, validates methods, and plans
 * loads and cascades.
 *
 * <p>This package produces the {@link io.github.fludakit.data.metadata.TypeMapping TypeMapping} every
 * generator consumes. It has no JavaPoet dependencies and writes no source files — it only reads
 * compiler elements and answers structural questions about the entity model.</p>
 *
 * <h2>Key classes</h2>
 *
 * <ul>
 *   <li>{@link io.github.fludakit.data.processor.analysis.MappedTypeBuilder} — builds and memoizes
 *       the compile-time {@code TypeMapping} for entities and value objects. Scans fields, classifies
 *       them (id, scalar, reference, embedded, entity collection, element collection). The central
 *       model builder.</li>
 *   <li>{@link io.github.fludakit.data.processor.analysis.EntityAnalysis} — turns a {@code TypeElement}
 *       into the compile-time model and resolves generated artifacts (metamodel, metadata, row mapper)
 *       idempotently. Whoever asks first triggers generation.</li>
 *   <li>{@link io.github.fludakit.data.processor.analysis.RepositoryTypeResolver} — resolves the entity
 *       type from a repository's generic supertype hierarchy ({@code DataRepository<T,ID>}, etc.).</li>
 *   <li>{@link io.github.fludakit.data.processor.analysis.RepositoryMethodValidator} — checks every
 *       abstract repository method before code generation.</li>
 *   <li>{@link io.github.fludakit.data.processor.analysis.QueryPlanCompiler} — compiles one method
 *       declaration into a {@code QueryPlan}: parsed name, predicates, sorts, seek terms, parameter
 *       bindings.</li>
 *   <li>{@link io.github.fludakit.data.processor.analysis.PathResolver} — resolves logical attribute
 *       paths to physical {@code ColumnRef}s with join information.</li>
 *   <li>{@link io.github.fludakit.data.processor.analysis.RelationshipLoadPlanner} — plans which
 *       relationships need runtime hydration (loader queries) vs JOIN projection.</li>
 *   <li>{@link io.github.fludakit.data.processor.analysis.CascadeSavePlanner} — plans write cascades
 *       for aggregate children.</li>
 * </ul>
 *
 * <p>Utility classes {@code AttributeTypes} and {@code TypeShapes} wrap compiler {@code Elements} and
 * {@code Types} so other classes need not hold them directly.</p>
 */
package io.github.fludakit.data.processor.analysis;
