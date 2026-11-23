package org.openmaptiles.layers;

import static com.onthegomap.planetiler.TestUtils.rectangle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.geo.GeometryException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openmaptiles.util.Imaginarium;

/** Imaginarium's changes to OpenMapTiles' output (see {@link Imaginarium}). */
class ImaginariumTest extends AbstractLayerTest {

  private static final Map<String, Object> TAGGED = Map.ofEntries(
    Map.entry("building:levels", "3"),
    Map.entry("building:min_level", "1"),
    Map.entry("roof:levels", "1,5"),
    Map.entry("height", "12 m"),
    Map.entry("min_height", "3"),
    Map.entry("roof:shape", "gabled"),
    Map.entry("roof:height", "4'"),
    Map.entry("roof:direction", "SW"),
    Map.entry("roof:orientation", "across"),
    Map.entry("roof:colour", "Dark Red"),
    Map.entry("roof:material", "roof_tiles"),
    Map.entry("building:colour", "#FA0"),
    Map.entry("building:material", "brick"),
    Map.entry("religion", "christian")
  );

  /** What TAGGED is in the tile: counts and the direction as numbers, colours as `#rrggbb`, lengths as tagged. */
  private static final Map<String, Object> EXPECTED = Map.ofEntries(
    Map.entry("_layer", "building"),
    Map.entry("_minzoom", 14),
    Map.entry("osm_id", 0L),
    Map.entry("building:levels", 3L),
    Map.entry("building:min_level", 1L),
    Map.entry("roof:levels", 1.5),
    Map.entry("height", "12 m"),
    Map.entry("min_height", "3"),
    Map.entry("roof:shape", "gabled"),
    Map.entry("roof:height", "4'"),
    Map.entry("roof:direction", 225L),
    Map.entry("roof:orientation", "<null>"),
    Map.entry("roof:colour", "#8b0000"),
    Map.entry("roof:material", "roof_tiles"),
    Map.entry("building:colour", "#ffaa00"),
    Map.entry("building:material", "brick"),
    Map.entry("religion", "christian")
  );

  private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
    var map = new HashMap<>(base);
    map.put(key, value);
    return map;
  }

  @Test
  void testBuildingFields() {
    assertFeatures(14, List.of(with(EXPECTED, "building", "church")),
      process(polygonFeature(with(TAGGED, "building", "church"))));
  }

  @Test
  void testBuildingPartFields() {
    assertFeatures(14, List.of(with(with(EXPECTED, "building:part", "tower"), "building", "<null>")),
      process(polygonFeature(with(TAGGED, "building:part", "tower"))));
  }

  @Test
  void testNoFieldsWhereUntagged() {
    var nothing = new HashMap<String, Object>();
    for (var key : EXPECTED.keySet()) {
      if (!key.startsWith("_") && !"osm_id".equals(key)) {
        nothing.put(key, "<null>");
      }
    }
    nothing.put("building", "yes");
    assertFeatures(14, List.of(nothing), process(polygonFeature(Map.of("building", "yes"))));
  }

  @Test
  void testNoFieldsBelowNearZoom() {
    // A building is made from z14 only: nothing at z13 at all.
    assertEquals(14, process(polygonFeature(with(TAGGED, "building", "yes"))).iterator().next().getMinZoom());
  }

  @ParameterizedTest
  @CsvSource(value = {
    "3, 3",
    "3.0, 3",
    "2.5, 2.5",
    "'2,5', 2.5",
    "' 4 ', 4",
    "-1, -1",
    "3;4, null",
    "yes, null",
    "3 m, null",
    "'', null",
  }, nullValues = "null")
  void testNumberOf(String tagged, String expected) {
    assertEquals(expected == null ? null : Double.valueOf(expected),
      Imaginarium.numberOf(tagged) == null ? null : Imaginarium.numberOf(tagged).doubleValue());
  }

  @Test
  void testWholeNumbersAreIntegers() {
    assertEquals(3L, Imaginarium.numberOf("3"));
    assertEquals(2.5, Imaginarium.numberOf("2.5"));
  }

  @ParameterizedTest
  @CsvSource(value = {
    "N, 0",
    "nne, 22.5",
    "E, 90",
    "SW, 225",
    "NNW, 337.5",
    "90, 90",
    "370, 10",
    "-90, 270",
    "112.5, 112.5",
    "297.7, 297.7",
    "up, null",
    "'', null",
  }, nullValues = "null")
  void testDegreesOf(String tagged, String expected) {
    var degrees = Imaginarium.degreesOf(tagged);
    assertEquals(expected == null ? null : Double.valueOf(expected), degrees == null ? null : degrees.doubleValue());
  }

  @ParameterizedTest
  @CsvSource(value = {
    "#ff0000, #ff0000",
    "#FF0000, #ff0000",
    "ff0000, #ff0000",
    "#f00, #ff0000",
    "red, #ff0000",
    "Red, #ff0000",
    "light_grey, #d3d3d3",
    "light grey, #d3d3d3",
    "dark-green, #006400",
    "cream, #fffdd0",
    "terracotta, #e2725b",
    "rebeccapurple, #663399",
    "#ff00, null",
    "#gg0000, null",
    "reddish, null",
    "'', null",
  }, nullValues = "null")
  void testColourOf(String tagged, String expected) {
    assertEquals(expected, Imaginarium.colourOf(tagged));
  }

  @Test
  void testRoadFields() {
    assertFeatures(14, List.of(Map.of(
      "_layer", "transportation",
      "_minzoom", 12,
      "class", "minor",
      "osm_id", 0L,
      "lanes", "2",
      "lit", "yes",
      "surface", "paved",
      "surface:osm", "asphalt"
    )), process(lineFeature(Map.of(
      "highway", "residential",
      "lanes", "2",
      "lit", "yes",
      "surface", "asphalt"
    ))));
  }

  @Test
  void testEntrance() {
    assertFeatures(14, List.of(Map.of(
      "_layer", "building",
      "_type", "point",
      "_minzoom", 14,
      "osm_id", 0L,
      "entrance", "main",
      "wheelchair", "yes"
    )), process(pointFeature(Map.of(
      "entrance", "main",
      "wheelchair", "yes"
    ))));
  }

  @Test
  void testPointHelipad() {
    // From the far zoom on, its id at the near zoom.
    assertFeatures(14, List.of(Map.of(
      "_layer", "aeroway",
      "_type", "point",
      "_minzoom", 12,
      "class", "helipad",
      "osm_id", 0L
    )), process(pointFeature(Map.of(
      "aeroway", "helipad"
    ))));
  }

  @Test
  void testFarZoomKeepsOnlyTunnelsAndBridges() throws GeometryException {
    var tunnel = new VectorTile.Feature("transportation", 1, VectorTile.encodeGeometry(rectangle(0, 1)),
      Map.of("brunnel", "tunnel"), 0);
    var road = new VectorTile.Feature("transportation", 2, VectorTile.encodeGeometry(rectangle(0, 1)),
      Map.of("class", "primary"), 0);
    assertEquals(List.of(tunnel), Imaginarium.kept("transportation", 12, List.of(tunnel, road)));
    assertEquals(List.of(tunnel, road), Imaginarium.kept("transportation", 14, List.of(tunnel, road)));
    assertEquals(List.of(), Imaginarium.kept("transportation", 13, List.of(tunnel, road)));
    assertEquals(List.of(), Imaginarium.kept("building", 12, List.of(road)));
    assertEquals(List.of(), profile.postProcessLayerFeatures("landuse", 12, List.of(road)));
  }

  @Test
  void testNothingSaysNothing() {
    assertNull(Imaginarium.colourOf(null));
    assertNull(Imaginarium.numberOf(null));
    assertNull(Imaginarium.degreesOf(null));
  }
}
