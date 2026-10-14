package io.github.fludakit.data.processor.analysis;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;

import io.github.fludakit.data.processor.emit.NavSupport;
import io.github.fludakit.data.processor.emit.RowWrites;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.Set;

/**
 * The FluDa attribute types the generated metamodel instantiates, plus the two lookups that decide
 * which one a member gets and what its type argument is.
 *
 * <p>They live apart from the emitters because the {@code _Entity} interface and the navigable member
 * classes spell the same attribute for the same member: a {@code SortableScalarAttribute} chosen in
 * one and a {@code ScalarAttribute} chosen in the other is a metamodel that does not type-check.
 *
 * <p>The two lookups below used to take {@link Elements} and {@link Types} as PARAMETERS, on the
 * reasoning that a stateless helper keeps the environment off its callers. Measured, it did the
 * opposite: eight fields across five classes existed for no reason except to be handed to these two
 * methods, and {@code NavSupport}'s javadoc had to explain why the three nav generators each kept
 * their own {@code Types}. Holding the pair once here retires all eight and shortens every call
 * site — the environment is owned in one place instead of being carried to it eleven times.
 *
 * <p>The constants stay {@code static}: they are the type names themselves, known without an
 * environment, and callers reach them as {@code AttributeTypes.FM_COLUMN} whether or not they hold
 * an instance.
 */
public final class AttributeTypes {

    private final Elements elements;
    private final Types types;

    public AttributeTypes(Elements elements, Types types) {
        this.elements = elements;
        this.types = types;
    }

    /** The marker every emitted metamodel type carries; the value is always the processor. */
    public static final ClassName MM_GENERATED = ClassName.get("jakarta.annotation", "Generated");

    /**
     * FluDa's extended attribute types ({@code io.github.fludakit.data.metamodel}): the Jakarta Data
     * 1.0 attribute subtypes the generated metamodel fields instantiate, so one field instance is
     * both the user-facing sort anchor and the runtime descriptor attribute.
     */
    public static final ClassName FM_COLUMN = ClassName.get("io.github.fludakit.data.metamodel", "ScalarAttribute");
    public static final ClassName FM_ID = ClassName.get("io.github.fludakit.data.metamodel", "IdAttribute");
    public static final ClassName FM_SORTABLE = ClassName.get("io.github.fludakit.data.metamodel", "SortableScalarAttribute");
    public static final ClassName FM_TEXT = ClassName.get("io.github.fludakit.data.metamodel", "TextScalarAttribute");
    public static final ClassName FM_EMBEDDED = ClassName.get("io.github.fludakit.data.metamodel", "EmbeddableAttribute");
    public static final ClassName FM_FK = ClassName.get("io.github.fludakit.data.metamodel", "EntityAttribute");
    public static final ClassName FM_OTM = ClassName.get("io.github.fludakit.data.metamodel", "ListAttribute");
    public static final ClassName FM_EC = ClassName.get("io.github.fludakit.data.metamodel", "ListAttribute");
    public static final ClassName FM_COLUMN_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "ScalarAttributeRecord");
    public static final ClassName FM_ID_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "IdAttributeRecord");
    public static final ClassName FM_SORTABLE_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "SortableScalarAttributeRecord");
    public static final ClassName FM_TEXT_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "TextScalarAttributeRecord");
    public static final ClassName FM_EMBEDDED_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "EmbeddableAttributeRecord");
    public static final ClassName FM_FK_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "EntityAttributeRecord");
    public static final ClassName FM_OTM_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "ListAttributeRecord");
    public static final ClassName FM_EC_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "ListAttributeRecord");

    /** Attribute-name types that get the spec's {@code TextAttribute} treatment. */
    public static final Set<String> METAMODEL_TEXT_TYPES = Set.of(
            "java.lang.String", "char", "java.lang.Character");

    /** Attribute-name types that get the spec's {@code SortableAttribute} treatment. */
    public static final Set<String> METAMODEL_SORTABLE_TYPES = Set.of(
            "boolean", "byte", "short", "int", "long", "float", "double",
            "java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
            "java.lang.Long", "java.lang.Float", "java.lang.Double",
            "java.math.BigDecimal", "java.math.BigInteger",
            "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime",
            "java.time.LocalTime", "java.time.OffsetDateTime", "java.time.OffsetTime",
            "java.time.ZonedDateTime", "java.util.Date");

    /** The spec's attribute-kind classification for a metamodel member of the given type name. */
    public MetamodelKind kindOf(String typeFqn) {
        if (typeFqn == null) {
            return MetamodelKind.PLAIN;
        }
        if (METAMODEL_TEXT_TYPES.contains(typeFqn)) {
            return MetamodelKind.TEXT;
        }
        if (METAMODEL_SORTABLE_TYPES.contains(typeFqn)) {
            return MetamodelKind.SORTABLE;
        }
        return isEnum(typeFqn) ? MetamodelKind.SORTABLE : MetamodelKind.PLAIN;
    }

    public enum MetamodelKind { PLAIN, SORTABLE, TEXT }

    /**
     * Whether the named type is an {@code enum}.
     *
     * <p>One question, asked in three places that each had their own copy of the answer — the
     * metamodel kind above, the row mapper's JDBC read, and the value {@code RowWrites} binds. Two
     * of those three held {@link Elements} for this and nothing else, which is why they were
     * generators that touched the environment. It lives here because this class already owns the
     * pair of services the answer needs.
     */
    public boolean isEnum(String typeFqn) {
        if (typeFqn == null) {
            return false;
        }
        TypeElement element = elements.getTypeElement(typeFqn);
        return element != null && element.getKind() == ElementKind.ENUM;
    }

    /** As {@link #isEnum(String)}, for a type already resolved to a JavaPoet name. */
    public boolean isEnum(TypeName type) {
        return type instanceof ClassName className && isEnum(className.canonicalName());
    }

    /** The class literal argument for a target FQN, erasing generics; {@code Object} when unknown. */
    public TypeName classArgOf(String fqn) {
        if (fqn == null) {
            return ClassName.OBJECT;
        }
        int angle = fqn.indexOf('<');
        String name = angle < 0 ? fqn : fqn.substring(0, angle);
        TypeElement te = elements.getTypeElement(name);
        if (te != null) {
            return TypeName.get(types.erasure(te.asType()));
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? ClassName.OBJECT : ClassName.get(name.substring(0, dot), name.substring(dot + 1));
    }
}
