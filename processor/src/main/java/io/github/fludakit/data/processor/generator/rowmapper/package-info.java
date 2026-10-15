/**
 * Row mapper generation: the classes that turn JDBC {@link java.sql.ResultSet} rows into domain
 * objects and write domain objects back to parameter buffers.
 *
 * <p>This package owns three responsibilities:</p>
 *
 * <ul>
 *   <li><b>{@link io.github.fludakit.data.processor.generator.rowmapper.RowMapperGenerator}</b> — emits
 *       the {@code RowMapper<T>} implementation that reads a row into the entity or value type.</li>
 *   <li><b>{@link io.github.fludakit.data.processor.generator.rowmapper.RowReads}</b> — builds the
 *       {@code CodeBlock} fragments that extract column values from a {@link java.sql.ResultSet}
 *       and hand them to the entity constructor or builder.</li>
 *   <li><b>{@link io.github.fludakit.data.processor.generator.rowmapper.RowWrites}</b> — builds the
 *       {@code CodeBlock} fragments that bind entity property values to a
 *       {@link java.sql.PreparedStatement} for INSERT, UPDATE, and DELETE statements.</li>
 * </ul>
 *
 * <h2>Split from the root emit package</h2>
 *
 * <p>These three classes were extracted into their own sub-package because they answer a single
 * question — <em>how does a row become an object, and how does an object become a row?</em> — that
 * is independent of the query compilation, navigation, and metadata concerns that live in the
 * parent package and its other sub-packages.</p>
 *
 * @see io.github.fludakit.data.processor.emit
 * @see io.github.fludakit.data.processor.generator.nav
 * @see io.github.fludakit.data.processor.generator.metadata
 */
package io.github.fludakit.data.processor.generator.rowmapper;
