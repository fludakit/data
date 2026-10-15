/**
 * Navigable member class generation: the {@code _<Entity><Field>} chain types for embedded,
 * relation, and element-collection members.
 *
 * <p>Each entity member that declares a navigation target ({@code @Embedded}, {@code @ManyToOne},
 * {@code @OneToMany}, {@code @ElementCollection}) gets its own generated class that exposes the
 * target's columns as a chain of single-property accessors. This package owns the generation of
 * those classes; the rest of the emit package generates the repository implementation, the row
 * mappers, and the metadata.</p>
 *
 * <p>Five classes:</p>
 * <ul>
 *   <li>{@link NavigationGenerator} — orchestrator: scans the entity's properties and dispatches
 *       to the specialized generator for each kind of navigation member</li>
 *   <li>{@link EmbeddedNavGenerator} — {@code @Embedded} value-object members</li>
 *   <li>{@link RelationNavGenerator} — {@code @ManyToOne} / {@code @OneToOne} relation members</li>
 *   <li>{@link ElementCollectionNavGenerator} — {@code @ElementCollection} members</li>
 *   <li>{@link NavSupport} — shared wiring: the base class fields, the ID accessor, the
 *       constructor that takes the parent's FK</li>
 * </ul>
 */
package io.github.fludakit.data.processor.generator.nav;
