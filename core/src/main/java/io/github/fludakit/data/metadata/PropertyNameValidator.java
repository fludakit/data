package io.github.fludakit.data.metadata;

import io.github.fludakit.data.metadata.TypeDefinition;

import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Validates entity and value object property names to ensure they don't use underscores.
 * 
 * <p>Underscores in property names are reserved for path navigation syntax in the generated
 * code. For example, a property path "customer_name" would be ambiguous: is it a single
 * property named "customer_name" or a navigation through "customer" to "name"?</p>
 * 
 * <p>To avoid this ambiguity, the processor rejects property names containing underscores
 * and suggests using camelCase instead.</p>
 * 
 * <p>Example validation:</p>
 * <pre>
 * PropertyNameValidator validator = new PropertyNameValidator();
 * List&lt;ValidationMessage&gt; messages = validator.validate(entity);
 * 
 * for (ValidationMessage msg : messages) {
 *     diagnostics.error(msg.message(), msg.element());
 * {
 * }
 * </pre>
 * 
 * <p>This class is stateless and thread-safe.</p>
 */
public class PropertyNameValidator {
    
    /**
     * Validates all property names in an entity TypeDefinition.
     * 
     * @param entity the entity to validate
     * @return list of validation messages (empty if all properties are valid)
     * @throws NullPointerException if entity is null
     */
    public List<ValidationMessage> validate(TypeDefinition.Entity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        
        List<ValidationMessage> messages = new ArrayList<>();
        
        // Validate ID property
        if (entity.id() != null) {
            validateProperty(entity.id(), messages);
        }
        
        // Validate version property
        if (entity.version() != null) {
            validateProperty(entity.version(), messages);
        }
        
        // Validate all other properties
        for (TypeDefinition.Prop prop : entity.properties()) {
            validateProperty(prop, messages);
        }
        
        return messages;
    }
    
    /**
     * Validates all property names in a value object TypeDefinition.
     * 
     * @param valueObject the value object to validate
     * @return list of validation messages (empty if all properties are valid)
     * @throws NullPointerException if valueObject is null
     */
    public List<ValidationMessage> validate(TypeDefinition.ValueObject valueObject) {
        Objects.requireNonNull(valueObject, "valueObject must not be null");
        
        List<ValidationMessage> messages = new ArrayList<>();
        
        for (TypeDefinition.Prop prop : valueObject.properties()) {
            validateProperty(prop, messages);
        }
        
        return messages;
    }
    
    /**
     * Validates a single property name.
     */
    private void validateProperty(TypeDefinition.Prop prop, List<ValidationMessage> messages) {
        String name = prop.name();
        
        if (name.contains("_")) {
            String message = String.format(
                "Property '%s' contains underscore, which is reserved for path navigation. " +
                "Use camelCase instead (e.g., '%s').",
                name,
                toCamelCase(name)
            );
            messages.add(new ValidationMessage(message, prop.name(), Diagnostic.Kind.ERROR));
        }
    }
    
    /**
     * Converts an underscore-separated name to camelCase.
     * 
     * <p>Example: "customer_name" → "customerName"</p>
     */
    private String toCamelCase(String name) {
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = false;
        
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            
            if (c == '_') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(c);
            }
        }
        
        return result.toString();
    }
    
    /**
     * Represents a validation message with severity, message text, and context.
     * 
     * @param message the validation message
     * @param context additional context (e.g., property name)
     * @param kind the severity (ERROR, WARNING, NOTE)
     */
    public record ValidationMessage(String message, String context, Diagnostic.Kind kind) {
        
        /**
         * Creates an error validation message.
         */
        public static ValidationMessage error(String message, String context) {
            return new ValidationMessage(message, context, Diagnostic.Kind.ERROR);
        }
        
        /**
         * Creates a warning validation message.
         */
        public static ValidationMessage warning(String message, String context) {
            return new ValidationMessage(message, context, Diagnostic.Kind.WARNING);
        }
        
        /**
         * Creates a note validation message.
         */
        public static ValidationMessage note(String message, String context) {
            return new ValidationMessage(message, context, Diagnostic.Kind.NOTE);
        }
    }
}
