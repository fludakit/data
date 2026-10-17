package io.github.fludakit.data.processor.generator.rowmapper;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.analysis.TypeModelBuilder;
import io.github.fludakit.data.processor.generator.metadata.Literals;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.processor.support.Props;
import java.util.ArrayList;
import java.util.List;

/**
 * Emits the read and construction of one flattened {@code EMBEDDED} value object in a generated row
 * mapper: every flattened leaf is read into a column-named local, and the value object is then built
 * under a presence guard — a subtree whose boxed leaves are all null stays null.
 *
 * <p>Split from {@link RowMapperGenerator} because it is a self-contained sub-concern — the flattened
 * value-object read with its per-subtree presence tracking — shared by the standalone
 * {@code <Entity>RowMapper} and the {@code <VO>RowMapper}.
 */
public final class EmbeddedValueReader {

    /** How one leaf column is read; supplied by the owner so the enum case stays in one place. */
    @FunctionalInterface
    public interface ReadExpression {
        CodeBlock read(TypeName type, ClassName metadataType, String property);
    }

    private final TypeModelBuilder typeMappingBuilder;
    private final ReadExpression readExpression;

    public EmbeddedValueReader(TypeModelBuilder typeMappingBuilder, ReadExpression readExpression) {
        this.typeMappingBuilder = typeMappingBuilder;
        this.readExpression = readExpression;
    }

    public void appendEmbeddedRead(MethodSpec.Builder mapRow, ClassName ownerMetadata,
                                   Prop.Embedded embedded, String entityName) {
        List<String> presence = new ArrayList<>();
        readEmbeddedLeaves(mapRow, ownerMetadata, embedded.target(),
                Names.columnBase(embedded.name()), embedded.name(), presence, entityName);
        boolean guarded = !presence.isEmpty();
        if (guarded) {
            mapRow.beginControlFlow("if ($L)", String.join(" || ", presence));
        }
        buildEmbeddedValue(mapRow, embedded.target(), Names.columnBase(embedded.name()), embedded.name());
        mapRow.addStatement("entity.$L($L)", MemberNames.setter(embedded.name()), embedded.name());
        if (guarded) {
            mapRow.endControlFlow();
        }
    }

    private void readEmbeddedLeaves(MethodSpec.Builder mapRow, ClassName ownerMetadata, TypeMapping<?> valueObject,
                                    String prefix, String path, List<String> presence, String entityName) {
        for (Prop p : valueObject.rowProps()) {
            if (p instanceof Prop.Reference) {
                continue; // fk-only children stay null on read, as at owner level
            }
            if (p instanceof Prop.Embedded) {
                readEmbeddedLeaves(mapRow, ownerMetadata, ((Prop.Embedded) p).target(),
                        prefix + "_" + Props.column(p), path + "." + p.name(), presence, entityName);
                continue;
            }
            String local = prefix + "_" + Props.column(p);
            TypeName type = typeMappingBuilder.memberType(valueObject.typeName(), p.name());
            if (!type.isPrimitive()) {
                mapRow.addStatement("$T $L = $L", type, local, readExpression.read(type, ownerMetadata, local));
                presence.add(local + " != null");
                continue;
            }
            mapRow.addStatement("$T $L = rs.$L($T.$L)", type, local, RowReads.primitiveGetter(path, type),
                    ownerMetadata, Literals.constName(local));
            mapRow.beginControlFlow("if (rs.wasNull())");
            mapRow.addStatement("throw new $T($S)", ClassName.get("java.sql", "SQLException"),
                    "NULL value in non-nullable primitive column \"" + local
                            + "\" mapped to " + entityName + "." + path);
            mapRow.endControlFlow();
        }
    }

    private void buildEmbeddedValue(MethodSpec.Builder mapRow, TypeMapping<?> valueObject, String prefix, String local) {
        mapRow.addStatement("$T $L = new $T()", typeMappingBuilder.classNameFor(valueObject.typeName()), local,
                typeMappingBuilder.classNameFor(valueObject.typeName()));
        for (Prop p : valueObject.rowProps()) {
            if (p instanceof Prop.Reference || p instanceof Prop.Embedded) {
                continue;
            }
            mapRow.addStatement("$L.$L($L)", local, MemberNames.setter(p.name()), prefix + "_" + Props.column(p));
        }
        for (Prop p : valueObject.rowProps()) {
            if (!(p instanceof Prop.Embedded)) {
                continue;
            }
            String childLocal = prefix + "_" + Props.column(p);
            List<String> terms = new ArrayList<>();
            RowReads.collectPresenceTerms(((Prop.Embedded) p).target(), childLocal, terms,
                    typeMappingBuilder::memberType);
            if (terms.isEmpty()) {
                buildEmbeddedValue(mapRow, ((Prop.Embedded) p).target(), childLocal, childLocal);
                mapRow.addStatement("$L.$L($L)", local, MemberNames.setter(p.name()), childLocal);
            } else {
                mapRow.beginControlFlow("if ($L)", String.join(" || ", terms));
                buildEmbeddedValue(mapRow, ((Prop.Embedded) p).target(), childLocal, childLocal);
                mapRow.addStatement("$L.$L($L)", local, MemberNames.setter(p.name()), childLocal);
                mapRow.endControlFlow();
            }
        }
    }
}
