/**
 * Jakarta Data annotation processor for FluDa repositories.
 *
 * <p>Pipeline owned by {@link RepositoryProcessor}:</p>
 * <ol>
 *   <li>{@link TypeDefinitionScanner} fills {@link TypeGraph} from {@code @Entity} /
 *       {@code @AggregateRoot} / {@code @ValueObject}</li>
 *   <li>{@code writer} types write graph artifacts ({@code *RowMapper}, {@code _Entity})</li>
 *   <li>{@link RepositoryAnalyzer} resolves each {@code @Repository} into a context</li>
 *   <li>{@code emitter.RepositoryMemberEmitter} runs method emitters; {@code writer.RepositoryWriter}
 *       writes {@code Repository_}</li>
 * </ol>
 *
 * <p>Naming: <em>Scanner/Analyzer</em> analyze, <em>Emitter</em> builds method bodies,
 * <em>Writer</em> writes compilation units via {@code Filer}. Core stays free of
 * {@code javax.lang.model}.</p>
 */
package io.github.fludakit.data.processor;
