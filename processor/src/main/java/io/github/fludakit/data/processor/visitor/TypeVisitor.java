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
        return switch (prop.kind()) {
            case SCALAR -> visitScalar((TypeDefinition.Prop.Scalar) prop);
            case EMBEDDED -> visitEmbedded((TypeDefinition.Prop.Embedded) prop);
            case TO_ONE -> visitToOne((TypeDefinition.Prop.ToOne) prop);
            case TO_MANY -> visitToMany((TypeDefinition.Prop.ToMany) prop);
            case ELEMENT_COLLECTION -> visitElementCollection((TypeDefinition.Prop.ElementCollection) prop);
            case COMPOSITION -> visitComposition((TypeDefinition.Prop.Composition) prop);
            case ID -> visitId((TypeDefinition.Prop.Id) prop);
            case VERSION -> visitVersion((TypeDefinition.Prop.Version) prop);
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
