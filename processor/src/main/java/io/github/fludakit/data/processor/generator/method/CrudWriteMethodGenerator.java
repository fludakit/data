package io.github.fludakit.data.processor.generator.method;

import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.processor.analysis.MethodResults;
import io.github.fludakit.data.processor.analysis.TypeModelRegistry;
import io.github.fludakit.data.processor.generator.CrudReadEmitter;
import io.github.fludakit.data.processor.generator.RepositoryContext;
import io.github.fludakit.data.processor.generator.WriteMethodEmitter;
import io.github.fludakit.data.processor.GeneratedArtifacts;
import io.github.fludakit.data.processor.support.GeneratedKeyCoercion;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * The write family and the plain CRUD built-ins, in the original dispatch order: the lifecycle batch,
 * the single-entity CRUD names, the batch CRUD names, the plain {@code findById}/{@code deleteById}/
 * {@code findAll} built-ins, then the single-entity annotated writes.
 *
 * <p>All five delegate to the existing emitters ({@link WriteMethodEmitter}, {@link CrudReadEmitter});
 * this generator owns the precedence between them and the other shapes, and publishes each method's
 * {@link QueryDefinition} so the {@code <Repository>Queries} holder covers every method.
 */
public final class CrudWriteMethodGenerator implements MethodGenerator {

    private final WriteMethodEmitter writeMethodEmitter;
    private final CrudReadEmitter crudReads;
    private final MethodResults methodResults;
    private final TypeModelRegistry typeMappingBuilder;
    private final GeneratedArtifacts registry;

    public CrudWriteMethodGenerator(WriteMethodEmitter writeMethodEmitter, CrudReadEmitter crudReads,
                             MethodResults methodResults, TypeModelRegistry typeMappingBuilder,
                             GeneratedArtifacts registry) {
        this.writeMethodEmitter = writeMethodEmitter;
        this.crudReads = crudReads;
        this.methodResults = methodResults;
        this.typeMappingBuilder = typeMappingBuilder;
        this.registry = registry;
    }

    @Override
    public boolean generate(MethodSpec.Builder builder, ExecutableElement method, ExecutableType resolved,
                            RepositoryContext emission) {
        String name = method.getSimpleName().toString();
        List<? extends VariableElement> parameters = method.getParameters();
        List<? extends TypeMirror> parameterTypes = resolved.getParameterTypes();
        int arity = parameters.size();
        TypeMirror returnMirror = resolved.getReturnType();
        ResultKind kind = methodResults.resultKindOf(returnMirror);
        ResultKind writeKind = writeKind(returnMirror, emission);
        boolean applicationId = applicationAssignedId(emission);

        // 5. Lifecycle batch — List<E> or E[] with @Insert/@Update/@Save/@Delete.
        if (arity == 1 && writeMethodEmitter.isEntitySequenceParameter(parameterTypes.get(0), emission.entity())
                && (method.getAnnotation(Insert.class) != null || method.getAnnotation(Update.class) != null
                || method.getAnnotation(Save.class) != null || method.getAnnotation(Delete.class) != null)) {
            publishLifecycleBatch(emission, name, method, writeKind, applicationId);
            writeMethodEmitter.emitLifecycleBatch(builder, method, parameters.get(0), parameterTypes.get(0),
                    emission.entity(), emission.constants(), emission.cascade());
            return true;
        }
        // 6. Single-entity CRUD by name — save, insert, update, delete.
        if (arity == 1 && writeMethodEmitter.isEntityParameter(parameterTypes.get(0), emission.entity())) {
            String value = MemberNames.parameterName(parameters.get(0), "entity");
            if (name.equals("delete")) {
                QueryDefinitions.publish(registry, emission, name, new QueryDefinition.DeleteById(writeKind));
                builder.addStatement("deleteById($L.$L())", value, MemberNames.getter(emission.entity().id().name()));
                return true;
            }
            if (name.equals("save")) {
                QueryDefinitions.publish(registry, emission, name, new QueryDefinition.Upsert(writeKind));
                writeMethodEmitter.emitSave(builder, method, value, emission.entity(), emission.constants(), emission.cascade());
                return true;
            }
            if (name.equals("insert") && emission.flavour().crud()) {
                QueryDefinitions.publish(registry, emission, name, new QueryDefinition.Insert(writeKind, applicationId));
                writeMethodEmitter.appendInsertAndChildCascades(builder, value, emission.entity(), emission.constants(), emission.cascade());
                if (returnMirror.getKind() != TypeKind.VOID) {
                    builder.addStatement("return $L", value);
                }
                return true;
            }
            if (name.equals("update") && emission.flavour().crud()) {
                QueryDefinitions.publish(registry, emission, name, new QueryDefinition.Update(writeKind));
                writeMethodEmitter.appendUpdateStatements(builder, value, emission.entity(), emission.constants(), emission.cascade());
                if (returnMirror.getKind() != TypeKind.VOID) {
                    builder.addStatement("return $L", value);
                }
                return true;
            }
        }
        // 7. Batch CRUD by name — saveAll, insertAll, updateAll, deleteAll.
        if (arity == 1) {
            if (name.equals("saveAll")) {
                QueryDefinitions.publish(registry, emission, name, new QueryDefinition.Upsert(writeKind));
                writeMethodEmitter.emitBatch(builder, method, parameters.get(0), emission.entity(), "save");
                return true;
            }
            if (name.equals("insertAll") && emission.flavour().crud()) {
                QueryDefinitions.publish(registry, emission, name, new QueryDefinition.Insert(writeKind, applicationId));
                writeMethodEmitter.emitBatch(builder, method, parameters.get(0), emission.entity(), "insert");
                return true;
            }
            if (name.equals("updateAll") && emission.flavour().crud()) {
                QueryDefinitions.publish(registry, emission, name, new QueryDefinition.Update(writeKind));
                writeMethodEmitter.emitBatch(builder, method, parameters.get(0), emission.entity(), "update");
                return true;
            }
            if (name.equals("deleteAll")) {
                QueryDefinitions.publish(registry, emission, name, new QueryDefinition.DeleteById(writeKind));
                builder.beginControlFlow("for ($T entity : $L)", typeMappingBuilder.classNameFor(emission.entity().typeName()),
                        MemberNames.parameterName(parameters.get(0), "entities"));
                builder.addStatement("delete(entity)");
                builder.endControlFlow();
                return true;
            }
        }
        // 8. Remaining CRUD built-ins — findById, deleteById, findAll.
        if (arity == 1 && name.equals("findById")) {
            QueryDefinitions.publish(registry, emission, name, CrudReadMethodGenerator.findById(emission, kind));
            crudReads.emitFindById(builder, MemberNames.parameterName(parameters.get(0), MemberNames.ID_PARAM),
                    emission, kind);
            return true;
        }
        if (arity == 1 && name.equals("deleteById")) {
            QueryDefinitions.publish(registry, emission, name, new QueryDefinition.DeleteById(writeKind));
            writeMethodEmitter.emitDeleteById(builder, MemberNames.parameterName(parameters.get(0), MemberNames.ID_PARAM),
                    emission.entity(), emission.constants(), emission.cascade());
            return true;
        }
        if (name.equals("findAll") && arity == 0) {
            QueryDefinitions.publish(registry, emission, name, CrudReadMethodGenerator.findAll(kind));
            crudReads.emitFindAll(builder, emission, kind);
            return true;
        }
        // 9. Single-entity annotated writes — @Insert, @Update, @Save, @Delete.
        if (arity == 1 && writeMethodEmitter.isEntityParameter(parameterTypes.get(0), emission.entity())) {
            String value = MemberNames.parameterName(parameters.get(0), "entity");
            if (method.getAnnotation(Insert.class) != null) {
                QueryDefinitions.publish(registry, emission, name, new QueryDefinition.Insert(writeKind, applicationId));
                writeMethodEmitter.appendInsertAndChildCascades(builder, value, emission.entity(), emission.constants(), emission.cascade());
                if (returnMirror.getKind() != TypeKind.VOID) {
                    builder.addStatement("return $L", value);
                }
                return true;
            }
            if (method.getAnnotation(Update.class) != null) {
                QueryDefinitions.publish(registry, emission, name, new QueryDefinition.Update(writeKind));
                writeMethodEmitter.appendUpdateStatements(builder, value, emission.entity(), emission.constants(), emission.cascade());
                if (returnMirror.getKind() != TypeKind.VOID) {
                    builder.addStatement("return $L", value);
                }
                return true;
            }
            if (method.getAnnotation(Save.class) != null) {
                QueryDefinitions.publish(registry, emission, name, new QueryDefinition.Upsert(writeKind));
                writeMethodEmitter.emitSave(builder, method, value, emission.entity(), emission.constants(), emission.cascade());
                return true;
            }
            if (method.getAnnotation(Delete.class) != null) {
                QueryDefinitions.publish(registry, emission, name, new QueryDefinition.DeleteById(writeKind));
                builder.addStatement("deleteById($L.$L())", value, MemberNames.getter(emission.entity().id().name()));
                return true;
            }
        }
        return false;
    }

    private void publishLifecycleBatch(RepositoryContext emission, String name, ExecutableElement method,
                                       ResultKind writeKind, boolean applicationId) {
        QueryDefinition definition;
        if (method.getAnnotation(Insert.class) != null) {
            definition = new QueryDefinition.Insert(writeKind, applicationId);
        } else if (method.getAnnotation(Update.class) != null) {
            definition = new QueryDefinition.Update(writeKind);
        } else if (method.getAnnotation(Save.class) != null) {
            definition = new QueryDefinition.Upsert(writeKind);
        } else {
            definition = new QueryDefinition.DeleteById(writeKind);
        }
        QueryDefinitions.publish(registry, emission, name, definition);
    }

    private ResultKind writeKind(TypeMirror returnMirror, RepositoryContext emission) {
        return methodResults.writeResultKindOf(returnMirror, methodResults.resultKindOf(returnMirror), emission.entity());
    }

    private boolean applicationAssignedId(RepositoryContext emission) {
        return !GeneratedKeyCoercion.isCoercible(
                typeMappingBuilder.memberType(emission.entity().typeName(), emission.entity().id().name()));
    }
}
