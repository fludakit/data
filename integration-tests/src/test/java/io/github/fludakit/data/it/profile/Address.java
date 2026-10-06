package io.github.fludakit.data.it.profile;

import io.github.fludakit.data.annotation.ValueObject;

@ValueObject
public class Address {
    String city;
    Integer zip;
    GeoPoint point;

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public Integer getZip() { return zip; }
    public void setZip(Integer zip) { this.zip = zip; }
    public GeoPoint getPoint() { return point; }
    public void setPoint(GeoPoint point) { this.point = point; }
}
