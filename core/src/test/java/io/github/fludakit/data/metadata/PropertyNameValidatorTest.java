package io.github.fludakit.data.metadata;

import io.github.fludakit.data.metadata.TypeDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PropertyNameValidator}.
 */
class PropertyNameValidatorTest {
    
    private PropertyNameValidator validator;
    
    @BeforeEach
    void setUp() {
        validator = new PropertyNameValidator();
    }
    
    @Test
    void validateEntityWithValidPropertyNames() {
        TypeDefinition.Prop.Id id = new TypeDefinition.Prop.Id(
            "id", "java.lang.Long", "id", TypeDefinition.Prop.Id.GeneratedType.AUTO);
        TypeDefinition.Prop.Scalar customerName = new TypeDefinition.Prop.Scalar(
            "customerName", "java.lang.String", "customer_name", true);
        TypeDefinition.Prop.Scalar orderDate = new TypeDefinition.Prop.Scalar(
            "orderDate", "java.time.LocalDate", "order_date", true);
        
        TypeDefinition.Entity entity = new TypeDefinition.Entity(
            "com.example.Order", "order", id, null,
            List.of(customerName, orderDate), List.of(), List.of(), List.of(), List.of());
        
        List<PropertyNameValidator.ValidationMessage> messages = validator.validate(entity);
        
        assertTrue(messages.isEmpty());
    }
    
    @Test
    void validateEntityWithUnderscoreInPropertyName() {
        TypeDefinition.Prop.Id id = new TypeDefinition.Prop.Id(
            "id", "java.lang.Long", "id", TypeDefinition.Prop.Id.GeneratedType.AUTO);
        TypeDefinition.Prop.Scalar customer_name = new TypeDefinition.Prop.Scalar(
            "customer_name", "java.lang.String", "customer_name", true);
        
        TypeDefinition.Entity entity = new TypeDefinition.Entity(
            "com.example.Order", "order", id, null,
            List.of(customer_name), List.of(), List.of(), List.of(), List.of());
        
        List<PropertyNameValidator.ValidationMessage> messages = validator.validate(entity);
        
        assertEquals(1, messages.size());
        PropertyNameValidator.ValidationMessage msg = messages.get(0);
        assertEquals(Diagnostic.Kind.ERROR, msg.kind());
        assertTrue(msg.message().contains("customer_name"));
        assertTrue(msg.message().contains("underscore"));
        assertTrue(msg.message().contains("customerName"));
        assertEquals("customer_name", msg.context());
    }
    
    @Test
    void validateEntityWithMultipleUnderscoreProperties() {
        TypeDefinition.Prop.Id id = new TypeDefinition.Prop.Id(
            "id", "java.lang.Long", "id", TypeDefinition.Prop.Id.GeneratedType.AUTO);
        TypeDefinition.Prop.Scalar customer_name = new TypeDefinition.Prop.Scalar(
            "customer_name", "java.lang.String", "customer_name", true);
        TypeDefinition.Prop.Scalar order_date = new TypeDefinition.Prop.Scalar(
            "order_date", "java.time.LocalDate", "order_date", true);
        
        TypeDefinition.Entity entity = new TypeDefinition.Entity(
            "com.example.Order", "order", id, null,
            List.of(customer_name, order_date), List.of(), List.of(), List.of(), List.of());
        
        List<PropertyNameValidator.ValidationMessage> messages = validator.validate(entity);
        
        assertEquals(2, messages.size());
        assertTrue(messages.stream().allMatch(m -> m.kind() == Diagnostic.Kind.ERROR));
    }
    
    @Test
    void validateEntityWithUnderscoreInIdProperty() {
        TypeDefinition.Prop.Id order_id = new TypeDefinition.Prop.Id(
            "order_id", "java.lang.Long", "order_id", TypeDefinition.Prop.Id.GeneratedType.AUTO);
        
        TypeDefinition.Entity entity = new TypeDefinition.Entity(
            "com.example.Order", "order", order_id, null,
            List.of(), List.of(), List.of(), List.of(), List.of());
        
        List<PropertyNameValidator.ValidationMessage> messages = validator.validate(entity);
        
        // The ID property is validated once
        assertTrue(messages.size() >= 1);
        assertTrue(messages.stream().anyMatch(m -> m.message().contains("order_id")));
    }
    
    @Test
    void validateValueObjectWithValidPropertyNames() {
        TypeDefinition.Prop.Scalar streetAddress = new TypeDefinition.Prop.Scalar(
            "streetAddress", "java.lang.String", "street_address", true);
        TypeDefinition.Prop.Scalar zipCode = new TypeDefinition.Prop.Scalar(
            "zipCode", "java.lang.String", "zip_code", true);
        
        TypeDefinition.ValueObject vo = new TypeDefinition.ValueObject(
            "com.example.Address",
            List.of(streetAddress, zipCode), List.of(), List.of(), List.of(), List.of());
        
        List<PropertyNameValidator.ValidationMessage> messages = validator.validate(vo);
        
        assertTrue(messages.isEmpty());
    }
    
    @Test
    void validateValueObjectWithUnderscoreProperty() {
        TypeDefinition.Prop.Scalar street_address = new TypeDefinition.Prop.Scalar(
            "street_address", "java.lang.String", "street_address", true);
        
        TypeDefinition.ValueObject vo = new TypeDefinition.ValueObject(
            "com.example.Address",
            List.of(street_address), List.of(), List.of(), List.of(), List.of());
        
        List<PropertyNameValidator.ValidationMessage> messages = validator.validate(vo);
        
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).message().contains("street_address"));
        assertTrue(messages.get(0).message().contains("streetAddress"));
    }
    
    @Test
    void validateRejectsNullEntity() {
        assertThrows(NullPointerException.class, () -> validator.validate((TypeDefinition.Entity) null));
    }
    
    @Test
    void validateRejectsNullValueObject() {
        assertThrows(NullPointerException.class, () -> validator.validate((TypeDefinition.ValueObject) null));
    }
    
    @Test
    void camelCaseConversion() {
        TypeDefinition.Prop.Id id = new TypeDefinition.Prop.Id(
            "id", "java.lang.Long", "id", TypeDefinition.Prop.Id.GeneratedType.AUTO);
        TypeDefinition.Prop.Scalar user_name = new TypeDefinition.Prop.Scalar(
            "user_name", "java.lang.String", "user_name", true);
        TypeDefinition.Prop.Scalar first_name = new TypeDefinition.Prop.Scalar(
            "first_name", "java.lang.String", "first_name", true);
        
        TypeDefinition.Entity entity = new TypeDefinition.Entity(
            "com.example.User", "user", id, null,
            List.of(user_name, first_name), List.of(), List.of(), List.of(), List.of());
        
        List<PropertyNameValidator.ValidationMessage> messages = validator.validate(entity);
        
        assertEquals(2, messages.size());
        
        // Check that suggestions are correct
        assertTrue(messages.stream().anyMatch(m -> m.message().contains("userName")));
        assertTrue(messages.stream().anyMatch(m -> m.message().contains("firstName")));
    }
}
