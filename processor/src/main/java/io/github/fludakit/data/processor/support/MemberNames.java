package io.github.fludakit.data.processor.support;

import io.github.fludakit.data.Names;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;

/**
 * The generated code's view of a compiled member: the accessor names it calls, whether an accessor
 * exists at all.
 *
 * <p>These are the facts that the metadata descriptor, the static metamodel and the row mapper all
 * agree on before any of them emits anything — a getter spelled one way in the descriptor and
 * another in the mapper is a class that does not compile, so the spelling lives in one place.
 * Every method here is a pure function of its arguments: nothing reads the
 * annotation-processing environment, so none of these needs a live round to answer.
 *
 * <p>Property-storage questions (column name, target type) live in {@link Props}, not here.
 * The one member question that DOES need the environment — whether a type declares or inherits a
 * method of a given name — is {@code TypeModelBuilder.hasMethod}, not a method here.</p>
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
     * two places must agree on it: {@link io.github.fludakit.data.processor.generator.RepositoryShellEmitter} declares the field, and
     * {@link io.github.fludakit.data.processor.generator.RepositoryImplGenerator} returns it from an accessor the repository declared. A rename in
     * either alone produces a class that does not compile.
     */
    public static final String JDBC_CLIENT_FIELD = "jdbcClient";

    /** The accessor name a repository declares to expose its injected {@code JdbcClient}. */
    private static final String GET_JDBC_CLIENT = "getJdbcClient";

    /** The erased name of the client type a repository accessor may expose. */
    private static final String JDBC_CLIENT = "io.github.fludakit.jdbc.JdbcClient";

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
     * The name a generated method body uses for {@code parameter}: the declared name when the
     * compilation kept it, otherwise {@code fallback} — {@code -parameters} is not guaranteed.
     */
    public static String parameterName(VariableElement parameter, String fallback) {
        String name = parameter.getSimpleName().toString();
        return name.isEmpty() ? fallback : name;
    }

    /**
     * Whether {@code method} is the {@code JdbcClient} escape hatch: {@code JdbcClient getJdbcClient()},
     * no parameters. Recognised on the declaration itself rather than through a marker interface, so a
     * repository asks for it by writing the method.
     *
     * <p>All three parts are checked, and the RETURN TYPE is the load-bearing one. Name and arity
     * alone would let {@code String getJdbcClient()} be answered with the wrong field and compile into
     * a repository whose accessor cannot be called; the type test keeps a wrong-shaped declaration on
     * the ordinary path, where it is reported as an unrecognised method like any other.</p>
     */
    public static boolean isJdbcClientAccessor(ExecutableElement method, String erasedReturnTypeName) {
        return method.getParameters().isEmpty()
                && GET_JDBC_CLIENT.equals(method.getSimpleName().toString())
                && JDBC_CLIENT.equals(erasedReturnTypeName);
    }
}
