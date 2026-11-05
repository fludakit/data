package io.github.fludakit.data.it.underscore;

import io.github.fludakit.data.annotation.ValueObject;

/**
 * Embedded value object whose FIELD NAME on {@link Site} contains an underscore
 * ({@code home_address}). Nested {@code geo_point} repeats the shape one level down, so a
 * path-flattening bug shows up at both depths.
 */
@ValueObject
public class Location {
    String city;
    Coordinates geo_point;

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public Coordinates getGeo_point() { return geo_point; }
    public void setGeo_point(Coordinates geoPoint) { this.geo_point = geoPoint; }
}
