/**
 * The processor's plan model: the value objects that flow from the analysis phase to the generation
 * phase.
 *
 * <p>These records and enums are the OUTPUT of {@code analysis} and the INPUT of {@code generate}:
 * {@code analysis} reads compiler elements and resolves them into {@link QueryPlan}, {@link LoadPlan}
 * and {@link WriteCascade}; {@code generate} consumes those and writes source. Nothing here reads the
 * compiler's {@code Elements}/{@code Types} services (a few records carry {@code VariableElement}/
 * {@code ExecutableElement} as the declaration they were resolved from, for diagnostics), and nothing
 * here imports JavaPoet — a generated artifact is referenced by its fully-qualified name as a
 * {@code String}, which an emitter resolves back to a {@code ClassName} only when it writes code.
 *
 * <p>Keeping this package free of {@code ProcessingEnvironment} and {@code Filer} is what makes the
 * analysis rules testable without driving javac.</p>
 */
package io.github.fludakit.data.processor.model;
