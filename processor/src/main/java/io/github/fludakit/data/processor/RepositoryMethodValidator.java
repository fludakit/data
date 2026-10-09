package io.github.fludakit.data.processor;

import jakarta.data.repository.Delete;
import jakarta.data.repository.Find;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Query;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;

import javax.annotation.processing.Messager;
import javax.lang.model.element.ExecutableElement;
import javax.tools.Diagnostic;

/** Validates operation annotations and free-style repository method declarations. */
final class RepositoryMethodValidator {

    private final Messager messager;

    RepositoryMethodValidator(Messager messager) {
        this.messager = messager;
    }

    boolean validate(ExecutableElement method, boolean dataRepository) {
        if (operationAnnotationCount(method) > 1) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "Repository method '" + method.getSimpleName()
                            + "' must not combine query and lifecycle annotations",
                    method);
            return false;
        }
        if (!dataRepository && !hasRepositoryOperationAnnotation(method)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "Abstract repository method '" + method.getSimpleName()
                            + "' must declare a Jakarta Data query/lifecycle annotation unless the repository"
                            + " extends DataRepository, BasicRepository, or CrudRepository",
                    method);
            return false;
        }
        return true;
    }

    private static boolean hasRepositoryOperationAnnotation(ExecutableElement method) {
        return method.getAnnotation(Find.class) != null
                || method.getAnnotation(Query.class) != null
                || method.getAnnotation(Insert.class) != null
                || method.getAnnotation(Update.class) != null
                || method.getAnnotation(Save.class) != null
                || method.getAnnotation(Delete.class) != null;
    }

    private static int operationAnnotationCount(ExecutableElement method) {
        int count = 0;
        count += method.getAnnotation(Find.class) == null ? 0 : 1;
        count += method.getAnnotation(Query.class) == null ? 0 : 1;
        count += method.getAnnotation(Insert.class) == null ? 0 : 1;
        count += method.getAnnotation(Update.class) == null ? 0 : 1;
        count += method.getAnnotation(Save.class) == null ? 0 : 1;
        count += method.getAnnotation(Delete.class) == null ? 0 : 1;
        return count;
    }
}
