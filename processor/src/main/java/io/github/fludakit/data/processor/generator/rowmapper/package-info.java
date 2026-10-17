/**
 * Row mapper generation: the classes that turn JDBC {@link java.sql.ResultSet} rows into domain
 * objects.
 *
 * <p>This package owns two responsibilities:</p>
 *
 * <ul>
 *   <li><b>{@link io.github.fludakit.data.processor.generator.rowmapper.RowMapperGenerator}</b> — emits
 *       the {@code RowMapper<T>} implementation that reads a row into the entity or value type.</li>
 *   <li><b>{@link io.github.fludakit.data.processor.generator.rowmapper.RowReads}</b> — builds the
 *       {@code CodeBlock} fragments that extract column values from a {@link java.sql.ResultSet}
 *       and hand them to the entity constructor or builder.</li>
 * </ul>
 *
 * <p>The write-side twin, {@code RowWrites}, now lives in the parent {@code generator} package: its
 * only consumers are the root and child write emitters there, so it has no reason to sit beside the
 * read-side classes.</p>
 *
 * <h2>Split from the root generator package</h2>
 *
 * <p>These classes were extracted into their own sub-package because they answer a single
 * question — <em>how does a row become an object?</em> — that is independent of the query
 * compilation, navigation, and metadata concerns that live in the parent package and its other
 * sub-packages.</p>
 *
 * @see io.github.fludakit.data.processor.generator
 * @see io.github.fludakit.data.processor.generator.nav
 * @see io.github.fludakit.data.processor.generator.metadata
 */
package io.github.fludakit.data.processor.generator.rowmapper;
