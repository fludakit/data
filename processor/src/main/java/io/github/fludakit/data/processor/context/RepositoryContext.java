package io.github.fludakit.data.processor.context;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.TypeDefinition;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import java.util.List;
import java.util.Map;

/**
 * Frozen repository analysis plan plus a separate {@link EmissionContext} for emission scratch.
 *
 * <p>Built once by {@code RepositoryAnalyzer}: entity, methods, row-mapper type, base repository
 * type for overrides, optional {@code JdbcClient} accessor. Emitters append SQL constants and
 * {@link MethodSpec}s on {@link #emission()}; {@code RepositoryWriter} consumes both.</p>
 */
public final class RepositoryContext {
    private final TypeDefinition entity;
    private final ClassName rowMapperType;
    private final DeclaredType baseRepositoryType;
    private final List<MethodContext> methods;
    private final ExecutableElement jdbcClientAccessor;
    private final EmissionContext emission = new EmissionContext();

    public RepositoryContext(TypeDefinition entity, ClassName rowMapperType,
                             DeclaredType baseRepositoryType, List<MethodContext> methods,
                             ExecutableElement jdbcClientAccessor) {
        this.entity = entity;
        this.rowMapperType = rowMapperType;
        this.baseRepositoryType = baseRepositoryType;
        this.methods = List.copyOf(methods);
        this.jdbcClientAccessor = jdbcClientAccessor;
    }

    public TypeDefinition entity() { return entity; }
    public ClassName rowMapperType() { return rowMapperType; }
    public DeclaredType baseRepositoryType() { return baseRepositoryType; }
    public List<MethodContext> methods() { return methods; }
    public ExecutableElement jdbcClientAccessor() { return jdbcClientAccessor; }

    /** Mutable emission scratch (constants + aux methods). */
    public EmissionContext emission() { return emission; }

    /** Delegates to {@link EmissionContext#constants()}. */
    public Map<String, String> constants() { return emission.constants(); }

    /** Delegates to {@link EmissionContext#auxMethods()}. */
    public List<MethodSpec> auxMethods() { return emission.auxMethods(); }

    /** Delegates to {@link EmissionContext#registerConstant(String, String)}. */
    public String registerConstant(String base, String sql) {
        return emission.registerConstant(base, sql);
    }
}
