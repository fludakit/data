package io.github.fludakit.data.it.profile;

import io.github.fludakit.data.annotation.Entity;
import io.github.fludakit.data.annotation.Identity;

/**
 * Second composition entity-collection target of the CV aggregate ({@code Profile.education}).
 * Same inverse-field contract as {@link WorkExperience}: bare {@code Profile profile} →
 * non-composing Reference on {@code profile_id}.
 */
@Entity
public class Education {
    @Identity Long id;
    String institution;
    String degree;
    int year;
    Profile profile;

    public Education() {}

    public Education(String institution, int year) {
        this.institution = institution;
        this.year = year;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getInstitution() { return institution; }
    public void setInstitution(String institution) { this.institution = institution; }
    public String getDegree() { return degree; }
    public void setDegree(String degree) { this.degree = degree; }
    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }
    public Profile getProfile() { return profile; }
    public void setProfile(Profile profile) { this.profile = profile; }
}
