package org.openmaptiles;

import static com.onthegomap.planetiler.TestUtils.assertContains;
import static com.onthegomap.planetiler.TestUtils.assertFeatureNear;
import static com.onthegomap.planetiler.util.Gzip.gunzip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.onthegomap.planetiler.TestUtils;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.archive.Tile;
import com.onthegomap.planetiler.config.Arguments;
import com.onthegomap.planetiler.geo.TileCoord;
import com.onthegomap.planetiler.mbtiles.Mbtiles;
import com.onthegomap.planetiler.reader.FileFormatException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.openmaptiles.util.Imaginarium;
import org.openmaptiles.util.VerifyMonaco;

/**
 * End-to-end tests for OpenMapTiles generation.
 * <p>
 * Generates an entire map for the smallest openstreetmap extract available (Monaco) and asserts that expected output
 * features exist
 * <p>
 * Imaginarium: only z12 and z14 are written (see {@link org.openmaptiles.util.Imaginarium}), z12 with water and aeroway
 * only; buildings are not merged, and an id each keeps the lines apart, so the counts are ours.
 */
class OpenMapTilesTest {

  @TempDir
  static Path tmpDir;
  private static Mbtiles mbtiles;

  @BeforeAll
  @Timeout(30)
  public static void runPlanetiler() throws Exception {
    Path dbPath = tmpDir.resolve("output.mbtiles");
    var osmPath = TestUtils.extractPathToResource(tmpDir, "monaco-latest.osm.pbf");
    // the Natural Earth tables clipped to Monaco (built with planetiler's scripts/natural-earth-test-shapefiles.sh),
    // copied out of planetiler-core's test jar
    var naturalEarthPath = TestUtils.extractPathToResource(tmpDir, "natural_earth_shapefiles.zip");
    var waterPath = TestUtils.extractPathToResource(tmpDir, "water_polygons.parquet");
    var lakeCenterlinesPath = TestUtils.extractPathToResource(tmpDir, "lake_centerline.parquet");
    OpenMapTilesMain.run(Arguments.of(
      // Override input source locations
      "osm_path", osmPath,
      "natural_earth_path", naturalEarthPath,
      "water_polygons_path", waterPath,
      "lake_centerlines_path", lakeCenterlinesPath,

      // Override temp dir location
      "tmpdir", tmpDir.resolve("tmp"),

      // Override output location
      "mbtiles", dbPath,

      // Imaginarium: the tunnel index it names
      "imaginarium_tunnels", "tunnels.pmtiles"
    ));

    mbtiles = Mbtiles.newReadOnlyDatabase(dbPath);
  }

  @AfterAll
  public static void close() throws IOException {
    mbtiles.close();
  }

  @Test
  void testMetadata() {
    Map<String, String> metadata = mbtiles.metadataTable().getAll();
    assertEquals("OpenMapTiles", metadata.get("name"));
    assertEquals("0", metadata.get("minzoom"));
    assertEquals("14", metadata.get("maxzoom"));
    assertEquals("baselayer", metadata.get("type"));
    assertEquals("pbf", metadata.get("format"));
    assertEquals("7.40921,43.72335,7.44864,43.75169", metadata.get("bounds"));
    assertEquals("7.42892,43.73752,14", metadata.get("center"));
    assertContains("openmaptiles.org", metadata.get("description"));
    // Imaginarium: the archive says its buildings are whole.
    assertEquals("whole", metadata.get("imaginarium:buildings"));
    // and which tunnel index is its own.
    assertEquals("tunnels.pmtiles", metadata.get("imaginarium:tunnels"));
    assertContains("openmaptiles.org", metadata.get("attribution"));
    assertContains("www.openstreetmap.org/copyright", metadata.get("attribution"));
  }

  @Test
  void ensureValidGeometries() throws Exception {
    Set<Tile> parsedTiles = TestUtils.getTiles(mbtiles);
    for (var tileEntry : parsedTiles) {
      var decoded = VectorTile.decode(gunzip(tileEntry.bytes()));
      for (VectorTile.Feature feature : decoded) {
        TestUtils.validateGeometry(feature.geometry().decode());
      }
    }
  }

  @Test
  void testContainsOceanPolyons() throws IOException {
    // Imaginarium: at z12 and z14, and no tile at all at the other zooms (see testOnlyFarAndNearZooms).
    for (int zoom : List.of(12, 14)) {
      var coord = TileCoord.aroundLngLat(7.4484, 43.70783, zoom);
      var features = VectorTile.decode(gunzip(mbtiles.getTile(coord.x(), coord.y(), zoom)));
      assertTrue(features.stream().anyMatch(f -> "water".equals(f.layer()) && "ocean".equals(f.tags().get("class"))),
        () -> "z" + zoom);
    }
  }

  @Test
  void testOnlyFarAndNearZooms() {
    // Imaginarium: only the far zoom (12) and the near (14) are written.
    assertEquals(Set.of(12, 14),
      TestUtils.getTiles(mbtiles).stream().map(tile -> tile.coord().z()).collect(Collectors.toSet()));
  }

  @Test
  void testFarZoomIsSlim() throws IOException {
    // Imaginarium: z12 has water and aeroway, nothing else.
    for (var tile : TestUtils.getTiles(mbtiles)) {
      if (tile.coord().z() == 12) {
        for (var feature : VectorTile.decode(gunzip(tile.bytes()))) {
          assertTrue(Set.of("water", "aeroway").contains(feature.layer()), feature::toString);
        }
      }
    }
  }

  @Test
  void testBuildingsCarryIdsAndFields() throws IOException {
    // Imaginarium: every building and building part its own feature with its id, and their fields at z14, the counts
    // as numbers and the colours as #rrggbb.
    int parts = 0;
    int levels = 0;

    for (var tile : TestUtils.getTiles(mbtiles)) {
      for (var feature : VectorTile.decode(gunzip(tile.bytes()))) {
        if (!"building".equals(feature.layer()) || feature.tags().containsKey("entrance")) {
          continue;
        }
        var tags = feature.tags();
        // Its id the feature's own (a way's × 10 + 2, a relation's + 3), not a field.
        assertTrue(feature.id() % 10 == 2 || feature.id() % 10 == 3, feature::toString);
        assertTrue(!tags.containsKey("osm_id") && !tags.containsKey("_osm_id"), feature::toString);
        assertTrue(!tags.containsKey("roof:orientation"), feature::toString);
        if (tags.containsKey("building:part")) {
          parts += 1;
        }
        if (tags.containsKey("building:levels")) {
          assertTrue(tags.get("building:levels") instanceof Number, feature::toString);
          levels += 1;
        }
        for (var key : List.of("building:colour", "roof:colour")) {
          if (tags.containsKey(key)) {
            assertTrue(tags.get(key).toString().matches("#[0-9a-f]{6}"), feature::toString);

          }
        }
      }
    }
    assertTrue(parts > 0, "parts");
    assertTrue(levels > 0, "levels");
  }

  @Test
  void testWrittenWithoutWhatNothingReads() throws IOException {
    // Imaginarium: ids only on buildings' outlines and parts and on transportation, landcover only what is kept, a poi's
    // name only a fuel station's, no house numbers' text; and what stands, as points in poi and lines in structure.
    Map<String, Integer> standing = new java.util.HashMap<>();
    for (var tile : TestUtils.getTiles(mbtiles)) {
      for (var feature : VectorTile.decode(gunzip(tile.bytes()))) {
        var tags = feature.tags();
        boolean ids = "transportation".equals(feature.layer()) ||
          ("building".equals(feature.layer()) && !tags.containsKey("entrance"));
        assertTrue(ids ? feature.id() != 0 : feature.id() == 0, feature::toString);
        switch (feature.layer()) {
          case "landcover" -> assertTrue(Imaginarium.LANDCOVER_KEPT.contains(tags.get("subclass")), feature::toString);
          case "structure" -> {
            assertTrue(Imaginarium.LINED.stream().anyMatch(lined -> lined.key().equals(tags.get("class")) &&
              lined.values().contains(tags.get("subclass"))), feature::toString);
            standing.merge((String) tags.get("subclass"), 1, Integer::sum);
          }
          case "housenumber" -> assertTrue(tags.isEmpty(), feature::toString);
          case "poi" -> {
            assertTrue(!tags.containsKey("rank") && !tags.containsKey("indoor"), feature::toString);
            assertTrue(tags.keySet().stream().noneMatch(key -> key.startsWith("name:") || key.startsWith("name_")),
              feature::toString);
            assertTrue(!tags.containsKey("name") || "fuel".equals(tags.get("class")), feature::toString);
            if (Imaginarium.STANDING.stream().anyMatch(each -> each.key().equals(tags.get("class")))) {
              standing.merge((String) tags.get("subclass"), 1, Integer::sum);
            }
          }
          case "water" -> assertTrue(!tags.containsKey("id"), feature::toString);
          case "waterway" -> assertTrue(!tags.containsKey("name"), feature::toString);
          case "transportation" -> assertTrue(!tags.containsKey("access") && !tags.containsKey("indoor"),
            feature::toString);
          default -> {
          }
        }
      }
    }
    for (String subclass : List.of("bench", "tree", "wall")) {
      assertTrue(standing.getOrDefault(subclass, 0) > 0, standing::toString);
    }
  }

  @Test
  void testBuildingsWholeInOneTile() throws IOException {
    // Imaginarium: each building and building part in one tile only but where marked `elsewhere`, whole there (nothing
    // of it at the edge of what the tile holds of it), and each part in the tile its building is in.
    Map<Long, TileCoord> homes = new java.util.HashMap<>();
    int parts = 0;
    int elsewhere = 0;
    for (var tile : TestUtils.getTiles(mbtiles)) {
      if (tile.coord().z() != 14) {
        continue;
      }
      var features = VectorTile.decode(gunzip(tile.bytes()));
      Set<Long> here = new java.util.HashSet<>();
      for (var feature : features) {
        if ("building".equals(feature.layer()) && !feature.tags().containsKey("elsewhere")) {
          here.add(feature.id());
        }
      }
      for (var feature : features) {
        if (!"building".equals(feature.layer()) || feature.tags().containsKey("entrance")) {
          continue;
        }
        if (feature.tags().containsKey("elsewhere")) {
          elsewhere += 1;
          continue;
        }
        var before = homes.put(feature.id(), tile.coord());
        assertTrue(before == null, () -> feature + " in " + before + " and " + tile.coord());
        if (feature.tags().get("parent")instanceof Long parent) {
          parts += 1;
          assertTrue(here.contains(parent), feature::toString);
        }
      }
    }
    assertTrue(parts > 0, "parts");
    assertTrue(elsewhere > 0, "elsewhere");
  }

  @Test
  void testContainsCountryName() {
    assertFeatureNear(mbtiles, "place", Map.of(
      "class", "country",
      "iso_a2", "MC",
      "name", "Monaco"
    ), 7.42769, 43.73235, 14, 14);
  }

  @Test
  void testContainsSuburb() {
    assertFeatureNear(mbtiles, "place", Map.of(
      "name", "Les Moneghetti",
      "class", "suburb"
    ), 7.41746, 43.73638, 14, 14);
  }

  @Test
  void testContainsBuildings() {
    assertFeatureNear(mbtiles, "building", Map.of(), 7.41919, 43.73401, 14, 14);
    // Imaginarium: each once where it is at home, and again marked `elsewhere` where it reaches another tile's margin or
    // comes within it of what that tile builds.
    assertNumFeatures("building", Map.of(), 14, 1325, Polygon.class);
    assertNumFeatures("building", Map.of(), 13, 0, Polygon.class);
    // Imaginarium: those with an address point in them (the housenumber layer's), the copies elsewhere too.
    assertNumFeatures("building", Map.of("addressed", true), 14, 178, Polygon.class);
  }

  @Test
  void testContainsHousenumber() {
    // Imaginarium: the points alone, the numbers not read.
    assertFeatureNear(mbtiles, "housenumber", Map.of(), 7.42117, 43.73652, 14, 14);
    assertNumFeatures("housenumber", Map.of(), 14, 231, Point.class);
  }

  @Test
  void testBoundary() {
    assertFeatureNear(mbtiles, "boundary", Map.of(
      "admin_level", 2L,
      "maritime", 1L,
      "disputed", 0L
    ), 7.41884, 43.72396, 14, 14);
  }

  @Test
  void testAeroway() {
    assertNumFeatures("aeroway", Map.of(
      "class", "heliport"
    ), 14, 1, Polygon.class);
    assertNumFeatures("aeroway", Map.of(
      "class", "helipad"
    ), 14, 11, Polygon.class);
  }

  @Test
  void testLandcover() {
    assertNumFeatures("landcover", Map.of(
      "class", "grass",
      "subclass", "park"
    ), 14, 20, Polygon.class);
    // Imaginarium: and the gardens (see Imaginarium.LANDCOVER_KEPT).
    assertNumFeatures("landcover", Map.of(
      "class", "grass",
      "subclass", "garden"
    ), 14, 33, Polygon.class);
  }

  @Test
  void testPoi() {
    assertNumFeatures("poi", Map.of(
      "class", "restaurant",
      "subclass", "restaurant"
    ), 14, 217, Point.class);
    assertNumFeatures("poi", Map.of(
      "class", "art_gallery",
      "subclass", "artwork"
    ), 14, 132, Point.class);
  }

  @Test
  void testLanduse() {
    assertNumFeatures("landuse", Map.of(
      "class", "residential"
    ), 14, 8, Polygon.class);
    assertNumFeatures("landuse", Map.of(
      "class", "hospital"
    ), 14, 4, Polygon.class);
  }

  @Test
  void testTransportation() {
    assertNumFeatures("transportation", Map.of(
      "class", "path",
      "subclass", "footway"
    ), 14, 1109, LineString.class);
    assertNumFeatures("transportation", Map.of(
      "class", "primary"
    ), 14, 340, LineString.class);
  }

  @Test
  void testTransportationName() {
    assertNumFeatures("transportation_name", Map.of(
      "name", "Boulevard du Larvotto",
      "class", "primary"
    ), 14, 9, LineString.class);
  }

  @Test
  void testWaterway() {
    assertNumFeatures("waterway", Map.of(
      "class", "stream"
    ), 14, 6, LineString.class);
  }

  @TestFactory
  Stream<DynamicTest> testVerifyChecks() {
    return VerifyMonaco.verify(mbtiles).results().stream()
      .map(check -> dynamicTest(check.name(), () -> {
        check.error().ifPresent(Assertions::fail);
      }));
  }

  @Test
  void testOldNaturalEarthSqliteFailsWithHint() {
    var sqlitePath = TestUtils.extractPathToResource(tmpDir, "natural_earth_vector.sqlite.zip");
    var arguments = Arguments.of("natural_earth_path", sqlitePath, "osm_path", tmpDir.resolve("monaco-latest.osm.pbf"));

    var exception = assertThrows(FileFormatException.class, () -> OpenMapTilesMain.run(arguments));
    assertContains("natural_earth_vector.sqlite.zip is missing 22 of the 22 shapefiles needed", exception.getMessage());
    assertContains("set --natural-earth-path to a directory to download them into", exception.getMessage());
  }

  private static void assertNumFeatures(String layer, Map<String, Object> attrs, int zoom,
    int expected, Class<? extends Geometry> clazz) {
    TestUtils.assertNumFeatures(mbtiles, layer, zoom, attrs, VerifyMonaco.MONACO_BOUNDS, expected, clazz);
  }
}
