package io.github.fludakit.data.it.profile;

import io.github.fludakit.data.annotation.ValueObject;

/**
 * Element-collection member of {@code Profile.contacts} — stored in the child table
 * {@code profiles_contacts} (fk {@code profile_id} + flattened leaves {@code kind}, {@code value}).
 */
@ValueObject
public class ContactPoint {
    String kind;
    String value;

    public ContactPoint() {}

    public ContactPoint(String kind, String value) {
        this.kind = kind;
        this.value = value;
    }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
