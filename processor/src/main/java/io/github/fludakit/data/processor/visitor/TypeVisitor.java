package io.github.fludakit.data.processor.visitor;

import io.github.fludakit.data.metadata.TypeDefinition;

/**
 * Visitor interface for traversing the TypeDefinition hierarchy using pattern matching.
 *
 * <p>Since TypeDefinition uses sealed records, visitors use instanceof pattern matching
 * rather than traditional accept() double-dispatch. This interface provides typed visit
 * methods that can be called after checking the type.</p>
 *
 * @param <T> the result type returned by visit methods
 */
public interface TypeVisitor<T> {

    // ── TypeDefinition variants ──────────────────────────────────────────

    default T visit(TypeDefinition type) {
        return switch (type) {
            case TypeDefinition.Entity e -> visitEntity(e);
            case TypeDefinition.Aggregate a -> visitAggregate(a);
            case TypeDefinition.ValueObject vo -> visitValueObject(vo);
        };
    }

    T visitEntity(TypeDefinition.Entity entity);

    T visitAggregate(TypeDefinition.Aggregate aggregate);

    T visitValueObject(TypeDefinition.ValueObject valueObject);

    // ── Prop kinds ───────────────────────────────────────────────────────

    default T visit(TypeDefinition.Prop prop) {
        return switch (prop) {
            case TypeDefinition.Prop.Scalar s -> visitScalar(s);
            case TypeDefinition.Prop.Embedded e -> visitEmbedded(e);
            case TypeDefinition.Prop.ToOne t -> visitToOne(t);
            case TypeDefinition.Prop.ToMany t -> visitToMany(t);
            case TypeDefinition.Prop.ElementCollection ec -> visitElementCollection(ec);
            case TypeDefinition.Prop.Composition c -> visitComposition(c);
            case TypeDefinition.Prop.Id id -> visitId(id);
            case TypeDefinition.Prop.Version v -> visitVersion(v);
        };
    }

    T visitScalar(TypeDefinition.Prop.Scalar scalar);

    T visitEmbedded(TypeDefinition.Prop.Embedded embedded);

    T visitToOne(TypeDefinition.Prop.ToOne toOne);

    T visitToMany(TypeDefinition.Prop.ToMany toMany);

    T visitElementCollection(TypeDefinition.Prop.ElementCollection elementCollection);

    T visitComposition(TypeDefinition.Prop.Composition composition);

    T visitId(TypeDefinition.Prop.Id id);

    T visitVersion(TypeDefinition.Prop.Version version);
}
