package io.github.fludakit.data.it.profile;

import io.github.fludakit.data.annotation.Entity;
import io.github.fludakit.data.annotation.Identity;

/**
 * Company entity referenced by WorkExperience via @Association.
 * When a WorkExperience is saved as part of a Profile aggregate cascade,
 * its associated Company should also be cascaded (inserted/updated).
 */
@Entity
public class Company {
    @Identity Long id;
    String name;
    String industry;

    public Company() {}

    public Company(String name, String industry) {
        this.name = name;
        this.industry = industry;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getIndustry() { return industry; }
    public void setIndustry(String industry) { this.industry = industry; }
}
