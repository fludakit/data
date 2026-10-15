package io.github.fludakit.data.processor.generator;

import com.squareup.javapoet.ClassName;
import io.github.fludakit.data.metadata.TypeMapping.Prop;

import io.github.fludakit.data.processor.generator.CrudReadEmitter;
import io.github.fludakit.data.processor.generator.RepositoryMethodCompiler;
import java.util.List;
import java.util.Map;
import io.github.fludakit.data.metadata.TypeMapping;

/**
 * The per-repository inputs every read-shape emitter reads.
 *
 * <p>Bundled because they travel together through the whole emitter family — {@link CrudReadEmitter}
 * answers every inherited read from this alone, and {@link RepositoryMethodCompiler} builds it once
 * per repository rather than passing six values to each shape. It is the READ-side projection of
 * {@link RepositoryEmission}, and {@link RepositoryEmission#readContext()} is the only place that
 * pairing is spelled out, so a read emitter cannot drift from what {@code generateMethod} built.
 *
 * @param entity            the entity model being generated for
 * @param constants         the SQL constant-name registry the emission registers its SQL in
 * @param mapperType        the row-mapper type the read executes with
 * @param hydrate           whether the read materialises then hydrates
 * @param crudFullMapper    the name of the per-repository full mapper when composing to-ones are
 *                          JOIN-projected onto the parent's own row, {@code null} otherwise
 * @param joinedReferences  those composing to-one references; they ride the parent's SELECT and get
 *                          no loader of their own
 */
public record EmissionContext(TypeMapping<?> entity, Map<String, String> constants, ClassName mapperType,
                       boolean hydrate, String crudFullMapper,
                       List<Prop.Reference> joinedReferences) {
}
