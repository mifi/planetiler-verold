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
  void testOutlinesAcrossOneAnotherAreOneBuilding() throws GeometryException {
    // An outline inside another, one lying across that, and a part inside the inner one: all one building, named by the
    // biggest; one beside them, its wall a hand's breadth into theirs, its own.
    var big = building(12, 0, 0, 100, 100, 50, 10, false);
    var inner = building(22, 40, 40, 80, 80, 60, 60, false);
    var across = building(42, 90, 10, 130, 30, 110, 20, false);
    var part = building(32, 50, 50, 70, 70, 60, 62, true);
    var beside = building(52, 99.95, 50, 140, 90, 120, 70, false);
    var here = byId(homed(0, 0, big, inner, across, part, beside));
    assertNull(here.get(12L).get("parent"));
    assertEquals(12L, here.get(22L).get("parent"));
    assertEquals(12L, here.get(42L).get("parent"));
    assertEquals(12L, here.get(32L).get("parent"));
    assertNull(here.get(52L).get("parent"));
  }

  @Test
  void testPartsUnderNoOutlineAreOneBuilding() throws GeometryException {
    // Parts lying across one another under no outline: one building, named by the biggest.
    var block = building(32, 10, 10, 60, 60, 35, 35, true);
    var tower = building(42, 40, 40, 70, 70, 55, 55, true);
    var here = byId(homed(0, 0, block, tower));
    assertNull(here.get(32L).get("parent"));
    assertEquals(32L, here.get(42L).get("parent"));
  }

  @Test
  void testThinPartInItsBuilding() throws GeometryException {
    // A part thinner than a sliver (a pillar) inside its building is its.
    var outline = building(12, 10, 10, 60, 60, 35, 35, false);
    var pillar = building(32, 20, 20, 20.05, 30, 20, 25, true);
    assertEquals(12L, byId(homed(0, 0, outline, pillar)).get(32L).get("parent"));
  }

  @Test
  void testOutlineAcrossAnothersPartIsOfItsBuilding() throws GeometryException {
    // A part reaching out of its outline, and a shelter under it: all one building.
    var outline = building(12, 0, 0, 40, 40, 20, 20, false);
    var part = building(32, 30, 0, 80, 40, 55, 20, true);
    var shelter = building(22, 60, 10, 75, 30, 67, 20, false);
    var here = byId(homed(0, 0, outline, part, shelter));
    assertEquals(12L, here.get(32L).get("parent"));
    assertEquals(12L, here.get(22L).get("parent"));
  }

  @Test
  void testOutlineInACourtyard() throws GeometryException {
    // The app fills a courtyard in: what stands in it is in the building round it.
    var factory = new org.locationtech.jts.geom.GeometryFactory();
    var round = factory.createPolygon(rectangle(0, 0, 100, 100).getExteriorRing(),
      new org.locationtech.jts.geom.LinearRing[]{rectangle(30, 30, 70, 70).getExteriorRing().reverse()});
    var outline = new VectorTile.Feature("building", 12, VectorTile.encodeGeometry(round),
      new HashMap<>(Map.of("_home_x", 50d, "_home_y", 50d, "_size", 100d, "building", "yes")), 0);
    var inside = building(22, 40, 40, 60, 60, 50, 50, false);
    assertEquals(12L, byId(homed(0, 0, outline, inside)).get(22L).get("parent"));
  }

  @Test
  void testOutlinesAtHomeInTwoTilesStayTwo() throws GeometryException {
    // Lying across each other over a tile's edge, each at home on its own side: each its own building, built by its own
    // tile (what is at home in another tile is not this one's to name).
    var west = building(12, 200, 10, 260, 60, 230, 30, false);
    var east = building(22, 250, 10, 300, 60, 270, 30, false);
    var here = byId(homed(0, 0, west, east));
    assertNull(here.get(12L).get("parent"));
    assertEquals(true, here.get(22L).get("elsewhere"));
    assertNull(here.get(22L).get("parent"));
  }

  @Test
  void testPartOfTheOutlineItSharesMost() throws GeometryException {
    // A big part whose middle is in a small building it overlaps but which lies mostly in a big one: the big one's. And
    // a part on a small building's corner that lies mostly outside every outline: still that building's (the app
    // builds the building as all of both, see its own-tiles.md).
    var small = building(12, 10, 10, 30, 30, 20, 20, false);
    var big = building(22, 60, 0, 200, 100, 130, 50, false);
    var part = building(32, 25, 20, 150, 80, 40, 40, true);
    var corner = building(42, 25, 25, 45, 45, 35, 35, true);
    var here = byId(homed(0, 0, small, big, part, corner));
    assertEquals(22L, here.get(32L).get("parent"));
    assertEquals(12L, here.get(42L).get("parent"));
  }

  @Test
  void testPartOfABuildingOfSeveralRings() throws GeometryException {
    // A multipolygon of two rings, a part in each: both name it.
    var rings =
      new org.locationtech.jts.geom.GeometryFactory().createMultiPolygon(new org.locationtech.jts.geom.Polygon[]{
        rectangle(10, 10, 40, 40), rectangle(60, 10, 90, 40)});
    var outline = new VectorTile.Feature("building", 13, VectorTile.encodeGeometry(rings),
      new HashMap<>(Map.of("_home_x", 50d, "_home_y", 25d, "_size", 80d, "building", "yes")), 0);
    var one = building(32, 15, 15, 25, 25, 20, 20, true);
    var two = building(42, 70, 20, 80, 30, 75, 25, true);
    var here = byId(homed(0, 0, outline, one, two));
    assertEquals(13L, here.get(32L).get("parent"));
    assertEquals(13L, here.get(42L).get("parent"));
  }

  @Test
  void testPartWithoutBuildingIsItsOwn() throws GeometryException {
    // At home in 0/0 with no building, its own there; and in 1/0, which it reaches, held for its neighbours, with no
    // parent: elsewhere, as a building is. A building it only touches is not its.
    var part = building(32, 200, 20, 300, 50, 250, 35, true);
    var touching = building(12, 150, 20, 200.05, 50, 175, 35, false);
    assertEquals(Map.of("building:part", "yes", "render_height", 5), byId(homed(0, 0, part, touching)).get(32L));
    assertEquals(Map.of("building:part", "yes", "render_height", 5, "elsewhere", true),
      byId(homed(1, 0, building(32, -56, 20, 44, 50, 250, 35, true))).get(32L));
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
