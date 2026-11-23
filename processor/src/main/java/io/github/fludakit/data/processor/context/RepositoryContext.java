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
 * The resolved plan for one repository, plus the output emitted for it.
 *
 * <p>Built once by {@code RepositoryScanner}: entity, methods, row-mapper type, base repository
 * type for overrides, optional `JdbcClient` accessor. Emitters append SQL constants and
 * {@link MethodSpec}s here; `RepositoryWriter` consumes both.</p>
 */
public final class RepositoryContext {

    /**
     * Where this repository stands in the resolve → plan → emit → write pipeline.
     *
     * <p>The status only moves forward, so a repository is emitted and written exactly once per
     * compilation — a later processing round re-visits it without touching it again.</p>
     */
    public enum Status {
        /** Resolved, but not emitted yet. */
        PLANNED,
        /** Emitted in full — ready to be written. */
        EMITTED,
        /** Written to its compilation unit. */
        WRITTEN,
        /** Emission failed — the repository is not written. */
        FAILED
    }

    private final TypeDefinition entity;
    private final ClassName rowMapperType;
    private final DeclaredType baseRepositoryType;
    private final List<MethodContext> methods;
    private final ExecutableElement jdbcClientAccessor;

    /** The SQL constants emitted for this repository, by constant name. */
    private final Map<String, String> constants = new LinkedHashMap<>();

    /** The methods emitted for this repository. */
    private final List<MethodSpec> auxMethods = new ArrayList<>();

    /** Whether this repository was emitted, failed to emit, or is still planned. */
    private Status status = Status.PLANNED;

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

    /** The SQL constants emitted for this repository, by constant name. */
    public Map<String, String> constants() { return constants; }

    /** The methods emitted for this repository. */
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

    /** Where this repository stands in the resolve → plan → emit pipeline. */
    public Status status() { return status; }

    /** Marks this repository as emitted in full. */
    public void markEmitted() { this.status = Status.EMITTED; }

    /** Marks this repository as written to its compilation unit. */
    public void markWritten() { this.status = Status.WRITTEN; }

    /** Marks this repository as failed to emit — it is not written. */
    public void markFailed() { this.status = Status.FAILED; }
}