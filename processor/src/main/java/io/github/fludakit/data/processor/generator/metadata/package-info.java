/**
 * Type-level descriptor generation: the metadata, metamodel, and query-definition artifacts that
 * describe an entity or repository as a whole.
 *
 * <p>Four classes:</p>
 * <ul>
 *   <li>{@link TypeMappingGenerator} — emits {@code <Entity>Metadata}, the reflection-free
 *       {@code TypeMapping} descriptor with column constants and the {@code resolve(path)} switch.</li>
 *   <li>{@link MetamodelGenerator} — emits the {@code _Entity} static metamodel interface
 *       (Jakarta Data 1.0 §3.3) with {@code UPPER_CASE} attribute constants.</li>
 *   <li>{@link QueryDefinitionWriter} — writes {@code <Repository>Queries}, one nested
 *       {@code QueryDefinition} per query-by-name method.</li>
 *   <li>{@link Literals} — renders query-definition records as JavaPoet {@code CodeBlock} literals
 *       for the generated {@code <Repository>Queries} holder.</li>
 * </ul>
 */
package io.github.fludakit.data.processor.generator.metadata;
