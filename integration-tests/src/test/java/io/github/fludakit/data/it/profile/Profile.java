package io.github.fludakit.data.it.profile;

import io.github.fludakit.data.annotation.AggregateRoot;
import io.github.fludakit.data.annotation.Identity;

import java.util.ArrayList;
import java.util.List;

/**
 * CV aggregate root: flattened embedded address plus two composition entity collections
 * ({@code workHistory}, {@code education} — targets manage their own tables via their fk
 * {@code profile_id}), an element collection of {@link ContactPoint} value objects
 * (child table {@code contact_points}), and a composing to-one {@link Preference}
 * (FK {@code preference_id} on the Profile table).
 */
@AggregateRoot
public class Profile {
    @Identity Long id;
    String fullName;
    Address address;
    Preference preference;
    List<WorkExperience> workHistory = new ArrayList<>();
    List<Education> education = new ArrayList<>();
    List<ContactPoint> contacts = new ArrayList<>();

    public Profile() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public Address getAddress() { return address; }
    public void setAddress(Address address) { this.address = address; }
    public Preference getPreference() { return preference; }
    public void setPreference(Preference preference) { this.preference = preference; }
    public List<WorkExperience> getWorkHistory() { return workHistory; }
    public void setWorkHistory(List<WorkExperience> workHistory) { this.workHistory = workHistory; }
    public List<Education> getEducation() { return education; }
    public void setEducation(List<Education> education) { this.education = education; }
    public List<ContactPoint> getContacts() { return contacts; }
    public void setContacts(List<ContactPoint> contacts) { this.contacts = contacts; }
}
