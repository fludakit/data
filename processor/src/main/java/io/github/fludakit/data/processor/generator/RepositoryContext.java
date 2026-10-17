package io.github.fludakit.data.processor.generator;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.analysis.RepositoryFlavour;
import io.github.fludakit.data.processor.model.EntityRelationship;
import io.github.fludakit.data.processor.model.LoadPlan;
import io.github.fludakit.data.processor.model.WriteCascade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-repository context that flows from analysis (the entity model and the interface's flavour)
 * through plan (the read {@link LoadPlan} and the write {@link WriteCascade}) into emission (the SQL
 * constants, the auxiliary methods and the published query-definition names).
 *
 * <p>This is the single object the method generators and emitters read, replacing the per-repository
 * record and its read-side projection. It is mutable only where the emitters append — the constants,
 * the auxiliary methods and the definition-name counts; every analysis/plan/hydration fact is fixed at
 * construction.
 */
public final class RepositoryContext {

    private final TypeMapping<?> entity;
    private final RepositoryFlavour flavour;
    private final LoadPlan loadPlan;
    private final WriteCascade cascade;
    private final ClassName mapperType;
    private final boolean hydrate;
    private final String crudFullMapper;

    private final Map<String, String> constants = new LinkedHashMap<>();
    private final List<MethodSpec> auxMethods = new ArrayList<>();
    private final Map<String, Integer> queryDefinitionNames = new HashMap<>();

    private final String repositoryFqn;

    public RepositoryContext(TypeMapping<?> entity, RepositoryFlavour flavour, LoadPlan loadPlan,
                             WriteCascade cascade, ClassName mapperType, boolean hydrate,
                             String crudFullMapper, String repositoryFqn) {
        this.entity = entity;
        this.flavour = flavour;
        this.loadPlan = loadPlan;
        this.cascade = cascade;
        this.mapperType = mapperType;
        this.hydrate = hydrate;
        this.crudFullMapper = crudFullMapper;
        this.repositoryFqn = repositoryFqn;
    }

    public TypeMapping<?> entity() {
        return entity;
    }

    public RepositoryFlavour flavour() {
        return flavour;
    }

    public ClassName mapperType() {
        return mapperType;
    }

    public boolean hydrate() {
        return hydrate;
    }

    public String crudFullMapper() {
        return crudFullMapper;
    }

    public WriteCascade cascade() {
        return cascade;
    }

    public Map<String, String> constants() {
        return constants;
    }

    public List<MethodSpec> auxMethods() {
        return auxMethods;
    }

    public Map<String, Integer> queryDefinitionNames() {
        return queryDefinitionNames;
    }

    public String repositoryFqn() {
        return repositoryFqn;
    }

    // ----------------------------------------------------------------- read-side delegations to the load plan

    public List<Prop.Reference> joinedReferences() {
        return loadPlan.joinedReferences();
    }

    public List<Prop.ElementCollection> hydratedCollections() {
        return loadPlan.hydratedCollections();
    }

    public Map<String, String> collectionMappers() {
        return loadPlan.collectionMappers();
    }

    public List<EntityRelationship> hydratedRelationships() {
        return loadPlan.hydratedRelationships();
    }
}
