package io.github.fludakit.data.processor.context;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.TypeDefinition;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolved repository plan plus mutable emission scratch.
 *
 * <p>Filled by {@code RepositoryAnalyzer}: entity, methods, row-mapper type, base repository
 * type for overrides, optional {@code JdbcClient} accessor. Emitters append SQL constants and
 * {@link MethodSpec}s; {@code RepositoryWriter} consumes the result.</p>
 */
public final class RepositoryContext {
    private final TypeDefinition entity;
    private final ClassName rowMapperType;
    private final DeclaredType baseRepositoryType;
    private final List<MethodContext> methods = new ArrayList<>();
    private ExecutableElement jdbcClientAccessor;

    // Emission state (mutable, appended by emitters)
    private final Map<String, String> constants = new LinkedHashMap<>();
    private final List<MethodSpec> auxMethods = new ArrayList<>();

    public RepositoryContext(TypeDefinition entity, ClassName rowMapperType, DeclaredType baseRepositoryType) {
        this.entity = entity;
        this.rowMapperType = rowMapperType;
        this.baseRepositoryType = baseRepositoryType;
    }

    public TypeDefinition entity() { return entity; }
    public ClassName rowMapperType() { return rowMapperType; }
    public DeclaredType baseRepositoryType() { return baseRepositoryType; }
    public List<MethodContext> methods() { return methods; }

    public ExecutableElement jdbcClientAccessor() { return jdbcClientAccessor; }

    public void setJdbcClientAccessor(ExecutableElement jdbcClientAccessor) {
        this.jdbcClientAccessor = jdbcClientAccessor;
    }

    public Map<String, String> constants() { return constants; }
    public List<MethodSpec> auxMethods() { return auxMethods; }

    /** Register a SQL constant, deduplicating by content. Returns the constant name. */
    public String registerConstant(String base, String sql) {
        String name = base;
        int suffix = 2;
        while (constants.containsKey(name) && !constants.get(name).equals(sql)) {
            name = base + "_" + suffix++;
        }
        constants.put(name, sql);
        return name;
    }
}
