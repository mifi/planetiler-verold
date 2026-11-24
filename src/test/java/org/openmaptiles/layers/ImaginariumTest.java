package org.openmaptiles.layers;

import static com.onthegomap.planetiler.TestUtils.rectangle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.geo.TileCoord;
import com.onthegomap.planetiler.stats.Stats;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openmaptiles.util.Imaginarium;
import org.openmaptiles.util.ImaginariumBuildings;

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
    Map.entry("_osm_id", 0L),
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
      if (!key.startsWith("_") && !"_osm_id".equals(key)) {
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
      "_osm_id", 0L,
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
      "_osm_id", 0L,
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
      "_osm_id", 0L
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

  /** A building in the z14 tile 0/0's units (256 a tile), at home at (x, y) in the same units, of a size. */
  private static VectorTile.Feature building(long id, double x0, double y0, double x1, double y1, double homeX,
    double homeY, boolean part) {
    var tags = new HashMap<String, Object>(Map.of("_home_x", homeX, "_home_y", homeY, "_size",
      Math.max(x1 - x0, y1 - y0), "render_height", 5));
    if (part) {
      tags.put("building:part", "yes");
      // (A point inside it, which its building is found by: here its home point.)
      tags.put("_inside_x", homeX);
      tags.put("_inside_y", homeY);
    } else {
      tags.put("building", "yes");
    }
    return new VectorTile.Feature("building", id, VectorTile.encodeGeometry(rectangle(x0, y0, x1, y1)), tags, 0);
  }

  private static List<VectorTile.Feature> homed(int x, int y, VectorTile.Feature... items) throws GeometryException {
    return ImaginariumBuildings.homed(TileCoord.ofXYZ(x, y, 14),
      new java.util.ArrayList<>(List.of(items)).stream().map(item -> item.copyWithExtraAttrs(Map.of())).toList(),
      Stats.inMemory());
  }

  private static Map<Long, Map<String, Object>> byId(List<VectorTile.Feature> items) {
    var by = new java.util.TreeMap<Long, Map<String, Object>>();
    for (var item : items) {
      by.put(item.id(), item.tags());
    }
    return by;
  }

  @Test
  void testBuildingAtHomeWholeElsewhereMarked() throws GeometryException {
    // A building at home in tile 0/0 reaching into tile 1/0: whole in the one, marked in the other's margin; one well
    // inside 0/0 not in 1/0 at all.
    var across = building(12, 200, 10, 300, 60, 220, 30, false);
    var inside = building(22, 10, 10, 60, 60, 30, 30, false);
    var here = byId(homed(0, 0, across, inside));
    assertEquals(Map.of("building", "yes", "render_height", 5), here.get(12L));
    assertEquals(Map.of("building", "yes", "render_height", 5), here.get(22L));
    // In tile 1/0 the same building is 256 further west.
    var next = byId(homed(1, 0, building(12, -56, 10, 44, 60, 220, 30, false), building(22, -246, 10, -196, 60, 30, 30,
      false)));
    assertEquals(Map.of("building", "yes", "render_height", 5, "elsewhere", true), next.get(12L));
    assertNull(next.get(22L));
  }

  @Test
  void testPartsGoWithTheirBuilding() throws GeometryException {
    // A building at home in 0/0, its part's own home point over in 1/0: the part is in 0/0 with its building, by its
    // id, and not in 1/0 at all, not even in the margin.
    var outline = building(12, 220, 10, 290, 60, 230, 30, false);
    var part = building(32, 260, 20, 285, 45, 270, 35, true);
    var here = byId(homed(0, 0, outline, part));
    assertEquals(12L, here.get(32L).get("parent"));
    var next = byId(homed(1, 0, building(12, -36, 10, 34, 60, 230, 30, false),
      building(32, 4, 20, 29, 45, 270, 35, true)));
    assertNull(next.get(32L));
    assertEquals(true, next.get(12L).get("elsewhere"));
  }

  @Test
  void testPartOfTheSmallestBuildingRoundIt() throws GeometryException {
    var big = building(12, 0, 0, 100, 100, 50, 10, false);
    var small = building(22, 40, 40, 80, 80, 60, 60, false);
    var part = building(32, 50, 50, 70, 70, 60, 62, true);
    assertEquals(22L, byId(homed(0, 0, big, small, part)).get(32L).get("parent"));
  }

  @Test
  void testPartWithoutBuildingIsItsOwn() throws GeometryException {
    var part = building(32, 200, 20, 300, 50, 250, 35, true);
    assertEquals(Map.of("building:part", "yes", "render_height", 5), byId(homed(0, 0, part)).get(32L));
    assertEquals(true,
      byId(homed(1, 0, building(32, -56, 20, 44, 50, 250, 35, true))).get(32L).get("elsewhere"));
  }

  @Test
  void testPartOfABuildingTooBigToReachStaysAtItsOwnHome() throws GeometryException {
    // A building wider than a part is carried past a tile's edge (PART_REACH): its part stays where its own home is,
    // whole there, naming its building all the same.
    var outline = building(12, 10, 10, 300, 60, 20, 30, false);
    var part = building(32, 250, 20, 290, 50, 270, 35, true);
    assertNull(byId(homed(0, 0, outline, part)).get(32L));
    var next = byId(homed(1, 0, building(12, -246, 10, 44, 60, 20, 30, false),
      building(32, -6, 20, 34, 50, 270, 35, true)));
    assertEquals(12L, next.get(32L).get("parent"));
  }

  @Test
  void testHomeIsTheMiddleOfTheCorners() {
    // An L: the middle of its six corners, as the app gives a building to a block by, not of its box.
    var shape =
      new org.locationtech.jts.geom.GeometryFactory().createPolygon(new org.locationtech.jts.geom.Coordinate[]{
        new org.locationtech.jts.geom.Coordinate(0, 0), new org.locationtech.jts.geom.Coordinate(6, 0),
        new org.locationtech.jts.geom.Coordinate(6, 1), new org.locationtech.jts.geom.Coordinate(1, 1),
        new org.locationtech.jts.geom.Coordinate(1, 6), new org.locationtech.jts.geom.Coordinate(0, 6),
        new org.locationtech.jts.geom.Coordinate(0, 0)});
    var middle = ImaginariumBuildings.middleOf(shape);
    assertEquals(14d / 6, middle.getX(), 1e-9);
    assertEquals(14d / 6, middle.getY(), 1e-9);
  }

  @Test
  void testBuildingBufferAsWideAsItIs() {
    var made = process(polygonFeature(Map.of("building", "yes"))).iterator().next();
    // The test's polygon is the whole world: 256 × 2^14 pixels at z14, and one more.
    assertEquals(4194306d, made.getBufferPixelsAtZoom(14));
  }

  @Test
  void testIdsTakenOffAsTilesAreWritten() {
    var road = new VectorTile.Feature("transportation", 52, VectorTile.encodeGeometry(rectangle(0, 1)),
      new HashMap<>(Map.of("class", "minor", "_osm_id", 5L)), 0);
    assertEquals(Map.of("class", "minor"), Imaginarium.unmarked(List.of(road)).getFirst().tags());
  }

  @Test
  void testNothingSaysNothing() {
    assertNull(Imaginarium.colourOf(null));
    assertNull(Imaginarium.numberOf(null));
    assertNull(Imaginarium.degreesOf(null));
  }
}
