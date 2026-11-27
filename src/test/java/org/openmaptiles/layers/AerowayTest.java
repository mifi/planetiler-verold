package org.openmaptiles.layers;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AerowayTest extends AbstractLayerTest {

  @Test
  void aerowayGate() {
    assertFeatures(14, List.of(Map.of(
      "class", "gate",
      "ref", "123",

      "_layer", "aeroway",
      "_type", "point",
      "_minzoom", 14,
      "_maxzoom", 14,
      "_buffer", 4d
    )), process(pointFeature(Map.of(
      "aeroway", "gate",
      "ref", "123"
    ))));
    assertFeatures(14, List.of(), process(lineFeature(Map.of(
      "aeroway", "gate"
    ))));
    assertFeatures(14, List.of(), process(polygonFeature(Map.of(
      "aeroway", "gate"
    ))));
  }

  @Test
  void aerowayLine() {
    assertFeatures(14, List.of(Map.of(
      "class", "runway",
      "ref", "123",

      "_layer", "aeroway",
      "_type", "line",
      "_minzoom", 12,
      "_maxzoom", 14,
      "_buffer", 4d
    )), process(lineFeature(Map.of(
      "aeroway", "runway",
      "ref", "123"
    ))));
    assertFeatures(14, List.of(), process(pointFeature(Map.of(
      "aeroway", "runway"
    ))));
  }

  /** Imaginarium: a runway's or a taxiway's bridge or tunnel, layer, tunnel, width and surface, as a road's, at z14. */
  @Test
  void aerowayLaid() {
    assertFeatures(14, List.of(Map.of(
      "class", "taxiway",
      "brunnel", "bridge",
      "layer", 1L,
      "width", "23",
      "surface", "paved",
      "surface:osm", "asphalt"
    )), process(lineFeature(Map.of(
      "aeroway", "taxiway",
      "bridge", "yes",
      "layer", "1",
      "width", "23",
      "surface", "asphalt"
    ))));
    assertFeatures(12, List.of(Map.of(
      "class", "taxiway",
      "brunnel", "<null>",
      "layer", "<null>",
      "width", "<null>",
      "surface", "<null>"
    )), process(lineFeature(Map.of(
      "aeroway", "taxiway",
      "bridge", "yes",
      "layer", "1",
      "width", "23",
      "surface", "asphalt"
    ))));
    assertFeatures(14, List.of(Map.of(
      "class", "runway",
      "brunnel", "<null>",
      "layer", "<null>",
      "surface", "unpaved",
      "surface:osm", "grass"
    )), process(polygonFeature(Map.of(
      "aeroway", "runway",
      "surface", "grass"
    )))); // And its tunnel's kind as tagged, a passage through a building told from a tunnel through a hill.
    assertFeatures(14, List.of(Map.of(
      "class", "taxiway",
      "brunnel", "tunnel",
      "tunnel", "building_passage"
    )), process(lineFeature(Map.of(
      "aeroway", "taxiway",
      "tunnel", "building_passage"
    ))));
  }

  @Test
  void aerowayPolygon() {
    assertFeatures(14, List.of(Map.of(
      "class", "runway",
      "ref", "123",

      "_layer", "aeroway",
      "_type", "polygon",
      "_minzoom", 12,
      "_maxzoom", 14,
      "_buffer", 4d
    )), process(polygonFeature(Map.of(
      "aeroway", "runway",
      "ref", "123"
    ))));
    assertFeatures(14, List.of(Map.of(
      "class", "runway",
      "ref", "123",
      "_layer", "aeroway",
      "_type", "polygon"
    )), process(polygonFeature(Map.of(
      "area:aeroway", "runway",
      "ref", "123"
    ))));
    assertFeatures(14, List.of(Map.of(
      "class", "heliport",
      "ref", "123",
      "_layer", "aeroway",
      "_type", "polygon"
    )), process(polygonFeature(Map.of(
      "aeroway", "heliport",
      "ref", "123"
    ))));
    assertFeatures(14, List.of(), process(lineFeature(Map.of(
      "aeroway", "heliport"
    ))));
    assertFeatures(14, List.of(), process(pointFeature(Map.of(
      "aeroway", "heliport"
    ))));
  }
}
