package io.github.fludakit.data.it;

import io.github.fludakit.data.annotation.ValueObject;

@ValueObject
public class GeoPoint {
    Double lat;
    Double lng;

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }
    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }
}
