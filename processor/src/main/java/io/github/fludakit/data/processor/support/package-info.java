/**
 * Infrastructure: diagnostics, generated-artifact registry, naming constants.
 *
 * <p>This package is the only place in the processor that names {@code Messager} (the compiler's
 * diagnostic channel) and {@code Filer} (the compiler's source-file writer). Everything above this
 * package uses {@link io.github.fludakit.data.processor.support.Diagnostics} and
 * {@link io.github.fludakit.data.processor.generator.RepositorySourceWriter} instead, which keeps the
 * compiler API isolated and testable.</p>
 *
 * <h2>Key classes</h2>
 *
 * <ul>
 *   <li>{@link io.github.fludakit.data.processor.support.Diagnostics} — single diagnostic channel
 *       interface. Production wraps {@code Messager}; tests use a collecting sink. Methods:
 *       {@code error()}, {@code note()}, {@code warning()}.</li>
 *   <li>{@link io.github.fludakit.data.processor.support.GeneratedArtifactRegistry} — session-scoped
 *       cache of everything emitted or claimed: row mappers, metadata types, metamodel types,
 *       generated type names, managed entities. Survives rounds. This is the backbone of the
 *       idempotent generation protocol: see
 *       {@link io.github.fludakit.data.processor.generator.ArtifactCoordinator} for the full explanation.</li>
 *   <li>{@link io.github.fludakit.data.processor.support.MemberNames} — known method/property name
 *       constants ({@code JDBC_CLIENT_FIELD}, {@code ID_PROPERTY}, etc.) and helper predicates.
 *       Centralizes the strings that appear in generated code so they don't drift.</li>
 * </ul>
 */
package io.github.fludakit.data.processor.support;
