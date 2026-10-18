/**
 * Jakarta Data annotation processor using visitor pattern.
 *
 * <p>Three-phase pipeline:
 * <ol>
 *   <li>{@link TypeMappingVisitor} scans entities, value objects, and aggregates</li>
 *   <li>{@link RepositoryVisitor} processes each repository interface</li>
 *   <li>Emitters generate code from resolved contexts</li>
 * </ol>
 */
package io.github.fludakit.data.processor;
