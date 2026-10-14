package io.github.fludakit.data.processor.support;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.TypeMapping.Prop;

import io.github.fludakit.data.processor.emit.RepositoryGenerator;
import io.github.fludakit.data.processor.emit.RepositoryShellEmitter;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * The generated code's view of a compiled member: the accessor names it calls, whether an accessor
 * exists at all, and the target type of a relationship property.
 *
 * <p>These are the facts that the metadata descriptor, the static metamodel and the row mapper all
 * agree on before any of them emits anything — a getter spelled one way in the descriptor and
 * another in the mapper is a class that does not compile, so the spelling lives in one place.
 * Every method is a pure function of its arguments; only {@link #hasMethodNamed} reads the
 * annotation-processing environment, and it takes {@link Elements} as a parameter rather than
 * reaching for it, so a caller can be exercised without a live round.
 */
public final class MemberNames {

    /**
     * The logical name of the identity PROPERTY. Two things are named {@code id} and they are not the
     * same thing, which is why there are two constants: this one is a member of the ENTITY — the
     * name a {@code @Id} field gets when it declares none, the name {@code By.ID} resolves to, and the
     * name the descriptor publishes.
     */
    public static final String ID_PROPERTY = "id";

    /**
     * The name a generated STATEMENT binds the identifier under: the JDBC parameter token of
     * {@code where "id" = :id} and the fallback name of a method parameter whose declared name is
     * unavailable. Not the property name — a read of an entity whose id property is called
     * {@code key} still binds {@code :id}, because the token names the parameter, not the member.
     */
    public static final String ID_PARAM = "id";

    /**
     * The generated field holding the repository's injected {@code JdbcClient}. Spelled here because
     * two places must agree on it: {@link RepositoryShellEmitter} declares the field, and
     * {@link RepositoryGenerator} returns it from an accessor the repository declared. A rename in
     * either alone produces a class that does not compile.
     */
    public static final String JDBC_CLIENT_FIELD = "jdbcClient";

    /** The erased name of the client type a repository accessor may expose. */
    private static final String JDBC_CLIENT = "io.github.fludakit.jdbc.JdbcClient";

    /**
     * Whether {@code method} is the {@code JdbcClient} escape hatch: {@code JdbcClient getJdbcClient()},
     * no parameters. Recognised on the declaration itself rather than through a marker interface, so a
     * repository asks for it by writing the method.
     *
     * <p>All three parts are checked, and the RETURN TYPE is the load-bearing one. Name and arity
     * alone would let {@code String getJdbcClient()} be answered with the wrong field and compile into
     * a repository whose accessor cannot be called; the type test keeps a wrong-shaped declaration on
     * the ordinary path, where it is reported as an unrecognised method like any other.</p>
     *
     * <p>The name is pinned rather than "any no-arg method returning the client" so the behaviour is
     * fully specified: a differently named method is not silently implemented, and there is no second
     * spelling for the processor and the reader to disagree about.</p>
     */
    public static boolean isJdbcClientAccessor(ExecutableElement method, Types types) {
        return method.getParameters().isEmpty()
                && "getJdbcClient".equals(method.getSimpleName().toString())
                && JDBC_CLIENT.equals(types.erasure(method.getReturnType()).toString());
    }

    private MemberNames() {
    }

    /** The bean getter generated code calls for {@code field}. */
    public static String getter(String field) {
        return "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

    /** The bean setter generated code calls for {@code field}. */
    public static String setter(String field) {
        return "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

    /** The {@code UPPER_SNAKE} constant-name fragment for a property, e.g. {@code lastName → LAST_NAME}. */
    public static String upperSnake(String name) {
        return Names.toSnakeCase(name).toUpperCase();
    }

    /**
     * The PascalCase form of {@code field}, used to build generated type names out of the property
     * they were derived from ({@code item → _OrderItem}).
     */
    public static String capitalize(String field) {
        return Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

    /**
     * The FQN of the type {@code p} points at, or {@code null} when the property is a plain scalar.
     * An {@link Prop.Embedded} answers its descriptor's type; the relationship kinds answer the
     * target (or child element) type they were declared with.
     */
    public static String memberTargetFqn(Prop prop) {
        if (prop instanceof Prop.Embedded embedded) {
            return embedded.target() == null ? null : embedded.target().typeName();
        }
        if (prop instanceof Prop.Reference reference) {
            return reference.targetTypeName();
        }
        if (prop instanceof Prop.EntityCollection entityCollection) {
            return entityCollection.targetTypeName();
        }
        if (prop instanceof Prop.ElementCollection elementCollection) {
            return elementCollection.elementTypeName();
        }
        return null;
    }

    /**
     * Whether {@code element} declares or inherits a method called {@code name}. Generated code calls
     * an accessor only after this check, so a bean-less type degrades to a NOTE instead of emitting a
     * call that does not compile.
     */
    public static boolean hasMethodNamed(Elements elements, TypeElement element, String name) {
        for (Element member : elements.getAllMembers(element)) {
            if (member.getKind() == ElementKind.METHOD && member.getSimpleName().contentEquals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The name a generated method body uses for {@code parameter}: the declared name when the
     * compilation kept it, otherwise {@code fallback} — {@code -parameters} is not guaranteed.
     */
    public static String parameterName(VariableElement parameter, String fallback) {
        String name = parameter.getSimpleName().toString();
        return name.isEmpty() ? fallback : name;
    }

    /** The property's own physical column (or fk column), {@code null} for a columnless container. */
    public static String memberColumn(Prop prop) {
        if (prop instanceof Prop.Id id) {
            return id.column();
        }
        if (prop instanceof Prop.Scalar scalar) {
            return scalar.column();
        }
        if (prop instanceof Prop.Embedded embedded) {
            return Names.columnBase(embedded.name());
        }
        if (prop instanceof Prop.Reference reference) {
            return reference.fkColumn();
        }
        return null; // EntityCollection / ElementCollection: no column on this table
    }
}
