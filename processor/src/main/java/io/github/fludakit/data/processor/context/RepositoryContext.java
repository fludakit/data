package io.github.fludakit.data.processor.context;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.TypeDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RepositoryContext {
    private final TypeDefinition entity;
    private final boolean crud;
    private final ClassName rowMapperType;
    private final String repositoryFqn;
    private final TypeDefinitionCache typeDefinitionCache;

    // Emission state (mutable, appended by emitters)
    private final Map<String, String> constants = new LinkedHashMap<>();
    private final List<MethodSpec> auxMethods = new ArrayList<>();
    private final Map<String, Integer> queryDefinitionNames = new LinkedHashMap<>();

    public RepositoryContext(TypeDefinition entity, boolean crud,
                             ClassName rowMapperType, String repositoryFqn) {
        this(entity, crud, rowMapperType, repositoryFqn, new TypeDefinitionCache());
    }

    public RepositoryContext(TypeDefinition entity, boolean crud,
                             ClassName rowMapperType, String repositoryFqn,
                             TypeDefinitionCache typeDefinitionCache) {
        this.entity = entity;
        this.crud = crud;
        this.rowMapperType = rowMapperType;
        this.repositoryFqn = repositoryFqn;
        this.typeDefinitionCache = typeDefinitionCache;
        
        // Pre-cache the root entity
        if (entity instanceof TypeDefinition.Entity e) {
            typeDefinitionCache.put(e.typeName(), entity);
        }
    }

    public TypeDefinition entity() { return entity; }
    public boolean crud() { return crud; }
    public ClassName rowMapperType() { return rowMapperType; }
    public String repositoryFqn() { return repositoryFqn; }
    public TypeDefinitionCache typeDefinitionCache() { return typeDefinitionCache; }
    public Map<String, String> constants() { return constants; }
    public List<MethodSpec> auxMethods() { return auxMethods; }
    public Map<String, Integer> queryDefinitionNames() { return queryDefinitionNames; }

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

    /** Generate a unique query definition name. */
    public String uniqueQueryName(String base) {
        Integer count = queryDefinitionNames.get(base);
        if (count == null) {
            queryDefinitionNames.put(base, 1);
            return base;
        }
        queryDefinitionNames.put(base, count + 1);
        return base + "_" + (count + 1);
    }
}
