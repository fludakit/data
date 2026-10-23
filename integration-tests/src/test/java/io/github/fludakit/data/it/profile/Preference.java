package io.github.fludakit.data.it.profile;

import io.github.fludakit.data.annotation.Entity;
import io.github.fludakit.data.annotation.Identity;

/**
 * Composing to-one definition of {@code Profile.preference}. The preference's lifecycle is owned
 * by the Profile aggregate — when a Profile is deleted, its Preference must also be deleted.
 * The FK column ({@code preference_id}) is on the Profile table.
 */
@Entity
public class Preference {
    public enum Visibility { PUBLIC, CIRCLE, PRIVATE }

    @Identity Long id;
    Visibility visibility;
    String defaultTheme;

    public Preference() {}

    public Preference(Visibility visibility, String defaultTheme) {
        this.visibility = visibility;
        this.defaultTheme = defaultTheme;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Visibility getVisibility() { return visibility; }
    public void setVisibility(Visibility visibility) { this.visibility = visibility; }
    public String getDefaultTheme() { return defaultTheme; }
    public void setDefaultTheme(String defaultTheme) { this.defaultTheme = defaultTheme; }
}
