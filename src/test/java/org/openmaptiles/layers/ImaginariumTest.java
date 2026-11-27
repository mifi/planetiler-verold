package org.openmaptiles.layers;

import static com.onthegomap.planetiler.TestUtils.newLineString;
import static com.onthegomap.planetiler.TestUtils.rectangle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.geo.TileCoord;
import com.onthegomap.planetiler.reader.osm.OsmElement;
import com.onthegomap.planetiler.stats.Stats;
import java.util.ArrayList;
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
    Map.entry("religion", "christian"),
    Map.entry("start_date", "~1923")
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
    Map.entry("religion", "christian"),
    Map.entry("start_date", 1923L)
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

  /** The keys OpenMapTiles also reads a height and a base of, as tagged and as numbers: what the app makes them of. */
  @Test
  void testBuildingHeightKeys() {
    assertFeatures(14, List.of(Map.of("_layer", "building", "building:height", "20 m", "building:min_height", "3",
      "levels", 4L, "min_level", 1L)), process(
        polygonFeature(Map.of("building", "yes", "building:height", "20 m",
          "building:min_height", "3", "levels", "4", "min_level", "1"))));
  }

  /**
   * What a building is used for, as its own keys say: an airport's terminal, a hotel, a workshop (beside what the other
   * layers make of the same keys).
   */
  @Test
  void testBuildingUses() {
    assertFeatures(14, List.of(Map.of("_layer", "building", "aeroway", "terminal", "building", "<null>")),
      buildingsOf(process(polygonFeature(Map.of("aeroway", "terminal")))));
    var uses = Map.<String, Object>of("tourism", "hotel", "leisure", "sports_hall", "healthcare", "clinic", "craft",
      "carpenter", "historic", "castle", "military", "barracks");
    assertFeatures(14, List.of(with(uses, "building", "yes")),
      buildingsOf(process(polygonFeature(with(uses, "building", "yes")))));
  }

  private static List<FeatureCollector.Feature> buildingsOf(Iterable<FeatureCollector.Feature> features) {
    List<FeatureCollector.Feature> buildings = new ArrayList<>();
    features.forEach(feature -> {
      if ("building".equals(feature.getLayer())) {
        buildings.add(feature);
      }
    });
    return buildings;
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
      "_minzoom", 14,
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
    // From the far zoom on.
    assertFeatures(14, List.of(Map.of(
      "_layer", "aeroway",
      "_type", "point",
      "_minzoom", 12,
      "class", "helipad"
    )), process(pointFeature(Map.of(
      "aeroway", "helipad"
    ))));
  }

  @Test
  void testFarZoomKeepsNoTransportation() throws GeometryException {
    var tunnel = new VectorTile.Feature("transportation", 1, VectorTile.encodeGeometry(rectangle(0, 1)),
      Map.of("brunnel", "tunnel"), 0);
    var road = new VectorTile.Feature("transportation", 2, VectorTile.encodeGeometry(rectangle(0, 1)),
      Map.of("class", "primary"), 0);
    assertEquals(List.of(), Imaginarium.kept("transportation", 12, List.of(tunnel, road)));
    assertEquals(List.of(road), Imaginarium.kept("water", 12, List.of(road)));
    assertEquals(List.of(tunnel, road), Imaginarium.kept("transportation", 14, List.of(tunnel, road)));
    assertEquals(List.of(), Imaginarium.kept("transportation", 13, List.of(tunnel, road)));
    assertEquals(List.of(), Imaginarium.kept("building", 12, List.of(road)));
    assertEquals(List.of(), profile.postProcessLayerFeatures("landuse", 12, List.of(road)));
  }

  /** A building in the z14 tile 0/0's units (256 a tile), at home at (x, y) in the same units, of a size. */
  private static VectorTile.Feature building(long id, double x0, double y0, double x1, double y1, double homeX,
    double homeY, boolean part) {
    var tags = new HashMap<String, Object>(Map.of("_home_x", homeX, "_home_y", homeY, "_size",
      Math.max(x1 - x0, y1 - y0), "height", "5"));
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
    assertEquals(Map.of("building", "yes", "height", "5"), here.get(12L));
    assertEquals(Map.of("building", "yes", "height", "5"), here.get(22L));
    // In tile 1/0 the same building is 256 further west.
    var next = byId(homed(1, 0, building(12, -56, 10, 44, 60, 220, 30, false), building(22, -246, 10, -196, 60, 30, 30,
      false)));
    assertEquals(Map.of("building", "yes", "height", "5", "elsewhere", true), next.get(12L));
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
    assertEquals(Map.of("building:part", "yes", "height", "5"), byId(homed(0, 0, part, touching)).get(32L));
    assertEquals(Map.of("building:part", "yes", "height", "5", "elsewhere", true),
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
    // A building some 419 pixels across at z14 (a ten-thousandth of the world): a buffer of that, and one more.
    var made = process(polygonFeatureWithArea(1e-8, Map.of("building", "yes"))).iterator().next();
    assertEquals(421d, made.getBufferPixelsAtZoom(14));
    assertNull(made.getAttrsAtZoom(14).get(ImaginariumBuildings.CUT));
  }

  @Test
  void testBuildingTooBigToMakeWholeIsCut() {
    // The test's polygon is the whole world, far over 16 tiles: cut at OpenMapTiles' margin, and marked so.
    var made = process(polygonFeature(Map.of("building", "yes"))).iterator().next();
    assertEquals(4d, made.getBufferPixelsAtZoom(14));
    assertEquals(true, made.getAttrsAtZoom(14).get(ImaginariumBuildings.CUT));
    assertNull(made.getAttrsAtZoom(14).get("_home_x"));
    // And the tiles keep it as it is, in every tile it reaches, neither at home nor elsewhere.
    var cut = new VectorTile.Feature("building", 52, VectorTile.encodeGeometry(rectangle(-4, 260)),
      new HashMap<>(Map.of("building", "yes", ImaginariumBuildings.CUT, true)), 0);
    try {
      assertEquals(List.of(cut), homed(3, 3, cut));
    } catch (GeometryException e) {
      throw new AssertionError(e);
    }
  }

  /** Tags from keys and values in turn. */
  private static Map<String, Object> tags(Object... pairs) {
    var tags = new HashMap<String, Object>();
    for (int at = 0; at + 1 < pairs.length; at += 2) {
      tags.put((String) pairs[at], pairs[at + 1]);
    }
    return tags;
  }

  private static VectorTile.Feature feature(String layer, long id, org.locationtech.jts.geom.Geometry geometry,
    Map<String, Object> tags) {
    return new VectorTile.Feature(layer, id, VectorTile.encodeGeometry(geometry), new HashMap<>(tags), 0);
  }

  @Test
  void testWrittenWithoutWhatNothingReads() {
    var line = rectangle(0, 1);
    var point = com.onthegomap.planetiler.TestUtils.newPoint(1, 1);
    // A way keeps its id (a tunnel's way, which the tunnel index names) and the fields read or to be read.
    var road = feature("transportation", 52, line, tags("class", "minor", "_osm_id", 5L, "access", "no",
      "bicycle", "yes", "foot", "yes", "horse", "no", "mtb_scale", "1", "official", 1, "ramp", 1, "toll", 1,
      "indoor", 1, "level", 1L, "sidewalk", "both", "lit", "yes", "maxspeed", "50", "maxheight", "4",
      "turn:lanes", "left|through", "surface:osm", "asphalt", "service:osm", "driveway"));
    assertEquals(List.of(feature("transportation", 52, line, tags("class", "minor", "level", 1L, "sidewalk", "both",
      "lit", "yes", "maxspeed", "50", "maxheight", "4", "turn:lanes", "left|through", "surface:osm", "asphalt",
      "service:osm", "driveway"))), Imaginarium.trimmed("transportation", List.of(road)));
    // A building keeps its id; an entrance, a point in its layer, none.
    var building = feature("building", 52, line, Map.of("building", "yes", "_osm_id", 5L));
    var entrance = feature("building", 51, point, Map.of("entrance", "main", "_osm_id", 5L));
    assertEquals(List.of(feature("building", 52, line, Map.of("building", "yes")),
      feature("building", 0, point, Map.of("entrance", "main"))),
      Imaginarium.trimmed("building", List.of(building, entrance)));
    // A poi without its id, its rank, indoor and names, but a fuel station's name (a charging point's too).
    var cafe = feature("poi", 51, point, Map.of("class", "cafe", "subclass", "cafe", "name", "Kaffe", "name:en",
      "Coffee", "name_en", "Coffee", "name_int", "Kaffe", "name:latin", "Kaffe", "rank", 3, "indoor", 1, "level", 2L));
    var fuel = feature("poi", 51, point, Map.of("class", "fuel", "subclass", "fuel", "name", "Circle K Majorstuen",
      "name:en", "Circle K", "rank", 1));
    assertEquals(List.of(feature("poi", 0, point, Map.of("class", "cafe", "subclass", "cafe", "level", 2L)),
      feature("poi", 0, point, Map.of("class", "fuel", "subclass", "fuel", "name", "Circle K Majorstuen"))),
      Imaginarium.trimmed("poi", List.of(cafe, fuel)));
    // A house number's point alone; water without its id; a stream without its names; the rest without their ids.
    assertEquals(List.of(feature("housenumber", 0, point, Map.of())),
      Imaginarium.trimmed("housenumber", List.of(feature("housenumber", 51, point, Map.of("housenumber", "7")))));
    assertEquals(List.of(feature("water", 0, line, Map.of("class", "lake"))),
      Imaginarium.trimmed("water", List.of(feature("water", 52, line, Map.of("class", "lake", "id", 5L)))));
    assertEquals(List.of(feature("waterway", 0, line, Map.of("class", "stream"))),
      Imaginarium.trimmed("waterway", List.of(feature("waterway", 52, line, Map.of("class", "stream", "name",
        "Akerselva", "name:en", "Aker river", "name_de", "Aker")))));
    for (var layer : List.of("landcover", "landuse", "aeroway")) {
      assertEquals(List.of(feature(layer, 0, line, Map.of("class", "x", "name", "y"))),
        Imaginarium.trimmed(layer, List.of(feature(layer, 52, line, Map.of("class", "x", "name", "y")))));
    }
    // What has nothing to take off is the very feature.
    var bare = feature("transportation", 52, line, Map.of("class", "minor"));
    assertEquals(bare, Imaginarium.trimmed("transportation", List.of(bare)).getFirst());
  }

  @ParameterizedTest
  @CsvSource(value = {
    "1923, 1923",
    "1923-05-01, 1923",
    "~1920, 1920",
    "1920s, 1920",
    "before 1900, 1900",
    "C19, null",
    "19th century, null",
    "9999, null",
    "'', null",
  }, nullValues = "null")
  void testYearOf(String tagged, Long expected) {
    assertEquals(expected, Imaginarium.yearOf(tagged));
  }

  @ParameterizedTest
  @CsvSource({
    "highway, street_lamp",
    "amenity, bench",
    "leisure, picnic_table",
    "natural, tree",
    "highway, crossing",
    "highway, traffic_signals",
    "highway, give_way",
    "railway, level_crossing",
    "railway, signal",
    "traffic_calming, hump",
    "traffic_calming, table",
    "emergency, fire_hydrant",
    "man_made, street_cabinet",
    "man_made, mast",
    "amenity, vending_machine",
    "historic, memorial",
  })
  void testStanding(String key, String value) {
    // By its key and its value as tagged, at the near zoom, a building's margin.
    var features = process(pointFeature(Map.of(key, value)));
    assertFeatures(14, List.of(Map.of(
      "_layer", "poi",
      "_type", "point",
      "_minzoom", 14,
      "_buffer", Imaginarium.STANDING_BUFFER,
      "class", key,
      "subclass", value
    )), features);
    // Only where mapped as a point: a bench drawn as a line is none.
    assertFeatures(14, List.of(), process(lineFeature(Map.of(key, value))));
  }

  @Test
  void testStandingTags() {
    assertFeatures(14, List.of(Map.of("_layer", "poi", "class", "highway", "subclass", "crossing", "crossing",
      "zebra")), process(pointFeature(Map.of("highway", "crossing", "crossing", "zebra"))));
    // A key's tags on any of its values: the lights at a crossing say so too.
    assertFeatures(14, List.of(Map.of("_layer", "poi", "class", "highway", "subclass", "traffic_signals", "crossing",
      "traffic_signals")), process(pointFeature(Map.of("highway", "traffic_signals", "crossing", "traffic_signals"))));
    assertFeatures(14, List.of(Map.of("_layer", "poi", "class", "man_made", "subclass", "tower", "height", "40",
      "tower:type", "communication")),
      process(pointFeature(Map.of("man_made", "tower", "height", "40", "tower:type", "communication"))));
    // A wind turbine, and no other generator; no power pole; a traffic calming of `no` none.
    assertFeatures(14, List.of(Map.of("_layer", "poi", "class", "power", "subclass", "generator", "height", "100")),
      process(pointFeature(Map.of("power", "generator", "generator:source", "wind", "height", "100"))));
    assertFeatures(14, List.of(), process(pointFeature(Map.of("power", "generator", "generator:source", "solar"))));
    // A power line's towers are its nodes (see testPowerLineEveryNode), and a pole none.
    assertFeatures(14, List.of(), process(pointFeature(Map.of("power", "tower"))));
    assertFeatures(14, List.of(), process(pointFeature(Map.of("power", "pole"))));
    assertFeatures(14, List.of(), process(pointFeature(Map.of("traffic_calming", "no"))));
  }

  @Test
  void testBusStopShelterAndBench() {
    assertFeatures(14, List.of(Map.of(
      "_layer", "poi",
      "subclass", "bus_stop",
      "shelter", "yes",
      "bench", "no"
    )), process(pointFeature(Map.of("highway", "bus_stop", "shelter", "yes", "bench", "no"))));
  }

  @ParameterizedTest
  @CsvSource({
    "barrier, fence",
    "barrier, wall",
    "barrier, hedge",
    "barrier, retaining_wall",
    "barrier, guard_rail",
    "power, line",
    "natural, tree_row",
    "natural, cliff",
  })
  void testLined(String key, String value) {
    var expected = new java.util.ArrayList<Map<String, Object>>(List.of(Map.of(
      "_layer", "structure",
      "_type", "line",
      "_minzoom", 14,
      "_buffer", Imaginarium.STANDING_BUFFER,
      "class", key,
      "subclass", value
    )));
    if ("cliff".equals(value)) {
      // (and OpenMapTiles' own, a layer we do not build)
      expected.add(Map.of("_layer", "mountain_peak"));
    }
    assertFeatures(14, expected, process(lineFeature(Map.of(key, value))));
    // A line only: a node tagged so is none.
    assertFeatures(14, List.of(), process(pointFeature(Map.of(key, value))));
  }

  @Test
  void testNotLined() {
    assertFeatures(14, List.of(), process(lineFeature(Map.of("power", "minor_line"))));
    assertFeatures(14, List.of(), process(lineFeature(Map.of("barrier", "yes"))));
    assertFeatures(14, List.of(), process(lineFeature(Map.of("barrier", "kerb"))));
  }

  @Test
  void testPowerLineEveryNode() {
    var features = process(lineFeature(Map.of("power", "line"))).iterator();
    assertEquals(0, features.next().getPixelToleranceAtZoom(14));
    var fence = process(lineFeature(Map.of("barrier", "fence"))).iterator();
    assertTrue(fence.next().getPixelToleranceAtZoom(14) > 0);
  }

  @Test
  void testParkingAndPitch() {
    assertFeatures(14, List.of(Map.of(
      "_layer", "landuse",
      "_type", "polygon",
      "_minzoom", 14,
      "class", "parking",
      "parking", "surface"
    ), Map.of("_layer", "poi")), process(polygonFeature(Map.of("amenity", "parking", "parking", "surface"))));
    assertFeatures(14, List.of(Map.of(
      "_layer", "landuse",
      "class", "pitch",
      "sport", "soccer"
    ), Map.of("_layer", "poi")), process(polygonFeature(Map.of("leisure", "pitch", "sport", "soccer"))));
  }

  @ParameterizedTest
  @CsvSource({
    "orchard, landuse",
    "vineyard, landuse",
    "allotments, landuse",
    "beach, natural",
    "sand, natural",
  })
  void testLandcoverKept(String subclass, String key) {
    assertFeatures(14, List.of(Map.of("_layer", "landcover", "subclass", subclass)),
      process(polygonFeature(Map.of(key, subclass))));
  }

  @Test
  void testMoreRoadFields() {
    assertFeatures(14, List.of(Map.of(
      "_layer", "transportation",
      "lanes:forward", "2",
      "lanes:backward", "1",
      "cycleway:both", "lane",
      "parking:both", "lane"
    )), process(lineFeature(Map.of("highway", "residential", "lanes:forward", "2", "lanes:backward", "1",
      "cycleway:both", "lane", "parking:both", "lane"))));
    assertFeatures(14, List.of(Map.of(
      "_layer", "transportation",
      "subclass", "steps",
      "step_count", "12",
      "handrail", "yes"
    )), process(lineFeature(Map.of("highway", "steps", "step_count", "12", "handrail", "yes"))));
    assertFeatures(14, List.of(Map.of(
      "_layer", "transportation",
      "class", "rail",
      "electrified", "contact_line"
    )), process(lineFeature(Map.of("railway", "rail", "electrified", "contact_line"))));
  }

  @Test
  void testTreeKind() {
    assertFeatures(14, List.of(Map.of(
      "_layer", "poi",
      "class", "natural",
      "subclass", "tree",
      "leaf_type", "broadleaved",
      "genus", "Betula",
      "height", "<null>"
    )), process(pointFeature(Map.of("natural", "tree", "leaf_type", "broadleaved", "genus", "Betula", "height",
      "12"))));
  }

  @Test
  void testNothingSaysNothing() {
    assertNull(Imaginarium.colourOf(null));
    assertNull(Imaginarium.numberOf(null));
    assertNull(Imaginarium.degreesOf(null));
  }

  @Test
  void testCutAtJunctions() {
    var nodes = new com.carrotsearch.hppc.LongArrayList();
    nodes.add(1, 2, 3);
    // The ways the transportation layer makes lines of, by their tags alone, are cut where they meet; nothing else.
    for (var tags : List.<Map<String, Object>>of(Map.of("highway", "footway"), Map.of("highway", "secondary"),
      Map.of("railway", "rail"), Map.of("aerialway", "chair_lift"), Map.of("route", "ferry"))) {
      assertTrue(Imaginarium.cutAtJunctions(new OsmElement.Way(1, tags, nodes)), tags::toString);
    }
    for (var tags : List.<Map<String, Object>>of(Map.of("building", "yes"), Map.of("waterway", "stream"),
      Map.of("railway", "platform"), Map.of())) {
      assertFalse(Imaginarium.cutAtJunctions(new OsmElement.Way(1, tags, nodes)), tags::toString);
    }
    // A pier is, but not a closed one, which is a polygon.
    var ring = new com.carrotsearch.hppc.LongArrayList();
    ring.add(1, 2, 3, 4, 1);
    assertTrue(Imaginarium.cutAtJunctions(new OsmElement.Way(1, Map.of("man_made", "pier"), nodes)));
    assertFalse(Imaginarium.cutAtJunctions(new OsmElement.Way(1, Map.of("man_made", "pier"), ring)));
    assertTrue(Imaginarium.cutAtJunctions(new OsmElement.Way(1, Map.of("highway", "footway"), ring)));
  }

  @Test
  void testPiecesOfAWayJoinedWithTheirJunctions() throws GeometryException {
    // A way cut where another meets it, its pieces running straight on: one line again, the point between kept, under
    // the way's id (its pieces' ids are Planetiler's: the way's, plus its place along the way times a power of ten).
    var tags = tags("class", "minor", "_osm_id", 5L);
    var pieces = List.of(feature("transportation", 52, newLineString(10, 10, 20, 10), tags),
      feature("transportation", 152, newLineString(20, 10, 30, 10), tags));
    var joined = profile.postProcessLayerFeatures("transportation", 14, pieces);
    assertEquals(1, joined.size());
    assertEquals(52, joined.getFirst().id());
    assertEquals(3, joined.getFirst().geometry().decode().getNumPoints());
    // A one-way way's too, in its own direction, whatever order its pieces come in.
    var oneway = tags("class", "minor", "oneway", 1, "_osm_id", 5L);
    var onewayPieces = List.of(feature("transportation", 152, newLineString(20, 10, 30, 10), oneway),
      feature("transportation", 52, newLineString(10, 10, 20, 10), oneway));
    var onewayJoined = profile.postProcessLayerFeatures("transportation", 14, onewayPieces);
    assertEquals(1, onewayJoined.size());
    assertEquals(newLineString(10, 10, 20, 10, 30, 10), onewayJoined.getFirst().geometry().decode());
    // A piece alone in a tile, the way's id.
    var alone = profile.postProcessLayerFeatures("transportation", 14,
      List.of(feature("transportation", 252, newLineString(30, 10, 40, 10), tags("class", "minor", "_osm_id", 5L))));
    assertEquals(52, alone.getFirst().id());
  }

  @Test
  void testClosedWayJoinedFromItsFirstNode() throws GeometryException {
    // A ring cut where a way ends on it (Oslo's Valkyrie platform and its steps): whole again, from its own first node,
    // its pieces in any order.
    var tags = tags("class", "path", "subclass", "platform", "_osm_id", 7L);
    var pieces = List.of(feature("transportation", 172, newLineString(20, 20, 10, 20, 10, 10), tags),
      feature("transportation", 72, newLineString(10, 10, 20, 10, 20, 20), tags));
    var joined = profile.postProcessLayerFeatures("transportation", 14, pieces);
    assertEquals(1, joined.size());
    assertEquals(72, joined.getFirst().id());
    assertEquals(newLineString(10, 10, 20, 10, 20, 20, 10, 20, 10, 10), joined.getFirst().geometry().decode());
  }
}
