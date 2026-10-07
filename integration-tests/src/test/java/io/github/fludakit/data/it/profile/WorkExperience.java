package io.github.fludakit.data.it.profile;

import io.github.fludakit.data.annotation.Entity;
import io.github.fludakit.data.annotation.Identity;

/**
 * Composition entity-collection target of {@code Profile.workHistory}. The bare {@code profile}
 * inverse field classifies as a non-composing {@code Reference} (owner {@code @Entity}, target
 * {@code @AggregateRoot}) mapping to the same {@code profile_id} column the aggregate's
 * {@code EntityList} loader walks — on direct reads it materialises as an id-only partial.
 */
@Entity
public class WorkExperience {
    @Identity Long id;
    String company;
    int yearStart;
    int yearEnd;
    Profile profile;

    public WorkExperience() {}

    public WorkExperience(String company, int yearStart, int yearEnd) {
        this.company = company;
        this.yearStart = yearStart;
        this.yearEnd = yearEnd;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public int getYearStart() { return yearStart; }
    public void setYearStart(int yearStart) { this.yearStart = yearStart; }
    public int getYearEnd() { return yearEnd; }
    public void setYearEnd(int yearEnd) { this.yearEnd = yearEnd; }
    public Profile getProfile() { return profile; }
    public void setProfile(Profile profile) { this.profile = profile; }
}
