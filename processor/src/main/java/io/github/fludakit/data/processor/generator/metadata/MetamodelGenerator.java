package io.github.fludakit.data.processor.generator.metadata;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;

import io.github.fludakit.data.processor.RepositoryProcessor;
import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.processor.analysis.TypeModelBuilder;
import io.github.fludakit.data.processor.generator.RepositorySourceWriter;
import io.github.fludakit.data.processor.generator.nav.NavigationGenerator;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.processor.support.Props;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Emits the Jakarta Data 1.0 §3.3 static metamodel: the {@code _Entity} interface and one navigable
 * member class ({@code _<Entity><Field>}) per embedded or relation member.
 *
 * <p>The generated interface carries the {@code UPPER_CASE} attribute-name constants and one field per
 * member; a navigable member instead gets a whole top-level class implementing the matching FluDa
 * attribute interface, so {@code _Order.item.name} type-checks instead of being a stringly-typed
 * path. Those classes are emitted by {@link NavigationGenerator}, which owns the {@code Filer} write
 * and the name claiming they need: a nav named {@code pkg._OrderItem} can collide with the
 * {@code OrderItem} aggregate root's own metamodel, and a collision degrades to a NOTE plus the plain
 * attribute record rather than failing the user's build.</p>
 *
 * <p>Writes go out through {@link RepositorySourceWriter}, so this emitter never sees {@code Filer}.
 * It holds no {@code Elements} and no {@code Types}: the only compile-time queries it needs are the
 * two in {@link AttributeTypes}, which it is handed already wired.</p>
 */
public final class MetamodelGenerator {

    /** Jakarta Data 1.0 §3.3 static metamodel types (string-form so the processor needs no new deps). */
    private static final ClassName MM_STATIC_METAMODEL =
            ClassName.get("jakarta.data.metamodel", "StaticMetamodel");

    private final AttributeTypes attributeTypes;
    private final Diagnostics diagnostics;
    private final RepositorySourceWriter sourceWriter;
    /** Emits the navigable member classes this interface's nav fields instantiate. */
    private final NavigationGenerator navigationGenerator;

    /**
     * Every generated type FQN the session claims, shared by reference with the processor: a nav class
     * is checked against ALL generated types — not just the metamodels — because a collision with a row
     * mapper or a repository impl fails the build just as loudly.
     */
    private final Set<String> generatedTypeNames;

    public MetamodelGenerator(AttributeTypes attributeTypes, Diagnostics diagnostics,
                       RepositorySourceWriter sourceWriter, Set<String> generatedTypeNames,
                       NavigationGenerator navigationGenerator) {
        this.attributeTypes = attributeTypes;
        this.diagnostics = diagnostics;
        this.sourceWriter = sourceWriter;
        this.generatedTypeNames = generatedTypeNames;
        this.navigationGenerator = navigationGenerator;
    }

    /**
     * Emits the Jakarta Data 1.0 §3.3 static metamodel: {@code _Entity}, a generated interface annotated
     * {@code @Generated} + {@code @StaticMetamodel(Entity.class)} whose {@code UPPER_CASE} String
     * constants hold entity ATTRIBUTE names (root members only) and whose mixed-case fields are
     * FluDa attribute records from {@code io.github.fludakit.data.metamodel}, each also carrying the
     * physical COLUMN — so one field instance is both the user-facing sort anchor and the runtime
     * descriptor attribute. Embedded and relation members are NAVIGABLE: each gets a
     * generated top-level member class ({@code _<Entity><Field>}) implementing the corresponding
     * attribute interface, instantiated as the field value; its leaf fields carry the owner-rooted
     * dot-path names. {@code <Entity>Metadata} returns these very instances. The member list comes
     * from {@link #collectMembers}, shared with the navigable member classes. A nav-class name
     * collision —
     * against ANY type in the session-global {@link #generatedTypeNames} registry, including another
     * entity's {@code _Entity} metamodel — degrades to a NOTE and the plain attribute record (spec
     * collision handling), never to a Filer error.
     */
    public void generateStaticMetamodel(TypeElement entityElement, TypeMapping<?> entity) {
        List<MmMember> members = collectMembers(entityElement, entity);
        ClassName owner = ClassName.get(entityElement);
        String packageName = owner.packageName();
        String simpleName = "_" + entityElement.getSimpleName();

        TypeSpec.Builder builder = TypeSpec.interfaceBuilder(simpleName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(AttributeTypes.MM_GENERATED)
                        .addMember("value", "$S", RepositoryProcessor.class.getName()).build())
                .addAnnotation(AnnotationSpec.builder(MM_STATIC_METAMODEL)
                        .addMember("value", "$T.class", owner).build());
        for (MmMember m : members) {
            // JavaPoet requires explicit visibility on interface fields (static/final are implicit).
            builder.addField(FieldSpec.builder(String.class, m.constant(),
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$S", m.path()).build());
        }
        // One navigable member class per embedded/relation member, collected by the nav generator and
        // written to the Filer after the interface itself (same round, distinct top-level types — see
        // NavigationGenerator#writePending). The collision guard
        // is the SESSION-GLOBAL generatedTypeNames registry (every _Entity metamodel, nav, metadata,
        // row mapper and repository impl FQN): a nav whose name collides with ANY generated type —
        // including another entity's metamodel (Order.item → pkg._OrderItem vs the OrderItem
        // root's own _OrderItem) — degrades to the NOTE + plain attribute record instead of colliding
        // at the Filer. This entity's own _Entity FQN is already pre-claimed in process()'s round
        // pre-scan, so metamodels win the name race independently of repository iteration order.
        for (MmMember m : members) {
            Prop p = m.prop();
            boolean navigable = p instanceof Prop.Embedded || p instanceof Prop.Reference
                    || p instanceof Prop.EntityCollection || p instanceof Prop.ElementCollection;
            ClassName navType = navigable
                    ? navigationGenerator.generateNav(entityElement, packageName, owner, m,
                            generatedTypeNames)
                    : null;
            if (navType != null) {
                builder.addField(FieldSpec.builder(navType, m.identifier(),
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("new $T()", navType).build());
            } else {
                builder.addField(FieldSpec.builder(memberAttrType(owner, m), m.identifier(),
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer(memberInitializer(owner, m, true)).build());
            }
        }

        sourceWriter.write(packageName + "." + simpleName, entityElement,
                JavaFile.builder(packageName, builder.build()).skipJavaLangImports(true).build());
        navigationGenerator.writePending(entityElement);
    }

    /** The member list of one descriptor: id first, then properties in declaration order. */
    private List<MmMember> collectMembers(Element origin, TypeMapping<?> model) {
        List<MmMember> members = new ArrayList<>();
        addMembers(members, new HashSet<>(), origin, model, "", "");
        return members;
    }

    private void addMembers(List<MmMember> members, Set<String> taken, Element origin,
                             TypeMapping<?> model, String pathPrefix, String columnPrefix) {
        if (model.id() != null) {
            addMember(members, taken, origin, model.id(), pathPrefix, columnPrefix);
        }
        for (Prop a : model.props()) {
            addMember(members, taken, origin, a, pathPrefix, columnPrefix);
        }
    }

    /**
     * One root member. A resolved {@link Prop.Embedded} contributes NO flat leaf recursion
     * here — its value object's leaves live on the navigable member class emitted by
     * {@link #generateStaticMetamodel}, and the flattened dot paths stay answerable through the
     * explicit {@code columns()} view. Duplicate identifiers keep the first member and report a NOTE
     * (spec collision handling).
     */
    private void addMember(List<MmMember> members, Set<String> taken, Element origin,
                           Prop prop, String pathPrefix, String columnPrefix) {
        String path = pathPrefix.isEmpty() ? prop.name() : pathPrefix + "." + prop.name();
        String identifier = path.replace('.', '_');
        if (!taken.add(identifier)) {
            diagnostics.note(
                    "static metamodel collision on '" + path + "': member '" + identifier
                            + "' already emitted; this path is skipped", origin);
            return;
        }
        String rawColumn = Props.column(prop);
        String column = rawColumn == null ? null : columnPrefix + rawColumn;
        members.add(new MmMember(path, column, prop));
    }

    /** The declared static type of one metamodel field / descriptor attribute, by member kind. */
    private TypeName memberAttrType(TypeName owner, MmMember member) {
        Prop p = member.prop();
        if (p instanceof Prop.Id) {
            return ParameterizedTypeName.get(AttributeTypes.FM_ID, owner);
        }
        if (p instanceof Prop.Scalar scalar) {
            return switch (attributeTypes.kindOf(scalar.typeFqn())) {
                case TEXT -> ParameterizedTypeName.get(AttributeTypes.FM_TEXT, owner);
                case SORTABLE -> ParameterizedTypeName.get(AttributeTypes.FM_SORTABLE, owner);
                default -> ParameterizedTypeName.get(AttributeTypes.FM_COLUMN, owner);
            };
        }
        if (p instanceof Prop.Embedded) {
            return ParameterizedTypeName.get(AttributeTypes.FM_EMBEDDED, owner,
                    attributeTypes.classArgOf(Props.targetFqn(p)));
        }
        if (p instanceof Prop.Reference) {
            return ParameterizedTypeName.get(AttributeTypes.FM_FK, owner,
                    attributeTypes.classArgOf(Props.targetFqn(p)));
        }
        if (p instanceof Prop.EntityCollection) {
            return ParameterizedTypeName.get(AttributeTypes.FM_OTM, owner,
                    attributeTypes.classArgOf(Props.targetFqn(p)));
        }
        return ParameterizedTypeName.get(AttributeTypes.FM_EC, owner,
                attributeTypes.classArgOf(Props.targetFqn(p)));
    }

    /**
     * The initializer of one member. In the metamodel file ({@code constRef}) the attribute name is
     * the interface's own UPPER constant and {@code <>} infers from the declared field type; in the
     * VO metadata literal mode the name is the dot path string and type arguments are explicit,
     * because {@code List.of(...)} targets do not reliably push inference into record constructors.
     */
    private CodeBlock memberInitializer(TypeName owner, MmMember member, boolean constRef) {
        CodeBlock name = CodeBlock.of("$S", member.prop().name());
        CodeBlock path = constRef ? CodeBlock.of("$L", member.constant()) : CodeBlock.of("$S", member.path());
        CodeBlock typeArgs = constRef ? CodeBlock.of("<>") : explicitTypeArgs(owner, member);
        Prop p = member.prop();
        if (p instanceof Prop.Id) {
            return CodeBlock.of("new $T$L($L, $L)", AttributeTypes.FM_ID_RECORD, typeArgs, name, path);
        }
        if (p instanceof Prop.Scalar scalar) {
            ClassName rec = switch (attributeTypes.kindOf(scalar.typeFqn())) {
                case TEXT -> AttributeTypes.FM_TEXT_RECORD;
                case SORTABLE -> AttributeTypes.FM_SORTABLE_RECORD;
                default -> AttributeTypes.FM_COLUMN_RECORD;
            };
            return CodeBlock.of("new $T$L($L, $L)", rec, typeArgs, name, path);
        }
        if (p instanceof Prop.Embedded) {
            return CodeBlock.of("new $T$L($L, $L)", AttributeTypes.FM_EMBEDDED_RECORD, typeArgs, name, path);
        }
        if (p instanceof Prop.Reference) {
            return CodeBlock.of("new $T$L($L, $L)", AttributeTypes.FM_FK_RECORD, typeArgs, name, path);
        }
        if (p instanceof Prop.EntityCollection) {
            return CodeBlock.of("new $T$L($L, $L)", AttributeTypes.FM_OTM_RECORD, typeArgs, name, path);
        }
        return CodeBlock.of("new $T$L($L, $L)", AttributeTypes.FM_EC_RECORD, typeArgs, name, path);
    }

    private CodeBlock explicitTypeArgs(TypeName owner, MmMember member) {
        Prop p = member.prop();
        if (p instanceof Prop.Id || p instanceof Prop.Scalar) {
            return CodeBlock.of("<$T>", owner);
        }
        return CodeBlock.of("<$T,$T>", owner,
                attributeTypes.classArgOf(Props.targetFqn(p)));
    }

}
