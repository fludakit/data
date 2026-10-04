package io.github.fludakit.data.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as a reference to an entity outside the aggregate (typically in another bounded
 * context).
 *
 * <p>Such a reference is persisted as a foreign key only: on write, the referenced entity's
 * identifier is extracted into the {@code <field>_id} column, and the referenced entity itself is
 * never cascaded. On read the reference is <em>not</em> eagerly resolved and the field is left
 * {@code null}; it is resolved lazily by the application when needed.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Association {
}
