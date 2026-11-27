package org.openmaptiles.layers;

import static com.onthegomap.planetiler.TestUtils.rectangle;

import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.geo.GeoUtils;
import com.onthegomap.planetiler.geo.GeometryException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BuildingTest extends AbstractLayerTest {

  @Test
  void testBuilding() {
    assertFeatures(13, List.of(Map.of(
      "colour", "<null>",
      "hide_3d", "<null>",
      "_layer", "building",
      "_type", "polygon",
      "_minzoom", 14,
      "_maxzoom", 14,
      // Imaginarium: as wide a buffer as the building is (this one the whole world, plus a pixel), so the tile it is at
      // home in holds all of it (see ImaginariumBuildings).
      "_buffer", 4d,
      // Imaginarium: nothing merges at z13 (building_merge_z13 is off), so no smaller minimum for it.
      "_minpixelsize", 2d
    )), process(polygonFeature(Map.of(
      "building", "yes"
    ))));
    assertFeatures(13, List.of(Map.of(
      "_layer", "building",
      "_type", "polygon"
    )), process(polygonFeature(Map.of(
      "building:part", "yes"
    ))));
    assertFeatures(13, List.of(), process(polygonFeature(Map.of(
      "building", "no"
    ))));
  }

  @Test
  void testIgnoreUndergroundBuilding() {
    assertFeatures(14, List.of(), process(polygonFeature(Map.of(
      "building", "yes",
      "location", "underground"
    ))));
  }

  /**
   * Imaginarium: a bridge mapped as a building is one, with its `man_made`, beside the bridge transportation has of it;
   * a bridge's outline alone is none.
   */
  @Test
  void testBridgeBuilding() {
    assertFeatures(14, List.of(Map.of(
      "_layer", "building",
      "_type", "polygon",
      "building", "yes",
      "man_made", "bridge"
    ), Map.of("_layer", "transportation", "class", "bridge")), process(polygonFeature(Map.of(
      "building", "yes",
      "man_made", "bridge"
    ))));
    assertFeatures(14, List.of(Map.of(
      "_layer", "building",
      "building:part", "yes",
      "man_made", "bridge"
    ), Map.of("_layer", "transportation", "class", "bridge")), process(polygonFeature(Map.of(
      "building:part", "yes",
      "man_made", "bridge"
    ))));
    assertFeatures(14, List.of(Map.of("_layer", "transportation")), process(polygonFeature(Map.of(
      "building", "no",
      "man_made", "bridge"
    ))));
    assertFeatures(14, List.of(), process(pointFeature(Map.of(
      "building", "yes",
      "man_made", "bridge"
    ))));
    assertFeatures(14, List.of(Map.of("_layer", "transportation", "class", "bridge")), process(polygonFeature(Map.of(
      "man_made", "bridge"
    ))));
  }

  @Test
  void testAirportBuildings() {
    assertFeatures(13, List.of(Map.of(
      "_layer", "building",
      "_type", "polygon"
    )), process(polygonFeature(Map.of(
      "aeroway", "terminal"
    ))));
    assertFeatures(13, List.of(Map.of(
      "_layer", "building",
      "_type", "polygon"
    )), process(polygonFeature(Map.of(
      "aeroway", "hangar"
    ))));
  }

  /**
   * Imaginarium: no `render_height` nor `render_min_height` (the app makes them of the tags carried), but OpenMapTiles'
   * height still the sort key, and a building or its base 3660 m up or more still none.
   */
  @Test
  void testRenderHeights() {
    assertFeatures(14, List.of(Map.of(
      "render_height", "<null>",
      "render_min_height", "<null>",
      "_sortkey", 5
    )), process(polygonFeature(Map.of(
      "building", "yes"
    ))));
    assertFeatures(14, List.of(Map.of(
      "render_height", "<null>",
      "render_min_height", "<null>",
      "_sortkey", 12
    )), process(polygonFeature(Map.of(
      "building", "yes",
      "building:min_height", "3",
      "building:height", "12"
    ))));
    assertFeatures(14, List.of(Map.of(
      "_sortkey", 44
    )), process(polygonFeature(Map.of(
      "building", "yes",
      "building:min_level", "3",
      "building:levels", "12"
    ))));
    assertFeatures(14, List.of(), process(polygonFeature(Map.of(
      "building", "yes",
      "building:min_level", "1500",
      "building:levels", "1500"
    ))));
  }

  @ParameterizedTest
  @CsvSource({
    "building:height, -3000000000",
    "building:height, -3",
    "building:levels, -1e9",
    "building:levels, -Infinity",
    "building:levels, NaN",
  })
  void testHeightBelowZeroOrNoNumberIsNone(String key, String value) {
    // Imaginarium: as untagged, its sort key in range, where -3000000000 m or -1e9 storeys made it Integer.MIN_VALUE.
    var features = process(polygonFeature(Map.of("building", "yes", key, value)));
    Assertions.assertEquals(5, features.iterator().next().getSortKey());
  }

  @ParameterizedTest
  @CsvSource({
    "2, 2",
    "2m, 2",
    "2 m, 2",
    "2.5 m, 3",
    "6', 2",
    "6'10\", 3",
  })
  void testRenderHeightUnits(String input, int expected) {
    assertFeatures(14, List.of(Map.of(
      "_sortkey", expected
    )), process(polygonFeature(Map.of(
      "building", "yes",
      "building:height", input
    ))));
  }

  @Test
  void testBuildingsNotMerged() throws GeometryException {
    // Imaginarium: each building its own feature at z14 (OpenMapTiles merges a tile's into multipolygons), and none at
    // z13 at all.
    var poly1 = new VectorTile.Feature(
      Building.LAYER_NAME,
      1,
      VectorTile.encodeGeometry(rectangle(10, 20)),
      Map.of(),
      0
    );
    var poly2 = new VectorTile.Feature(
      Building.LAYER_NAME,
      2,
      VectorTile.encodeGeometry(rectangle(20, 10, 22, 20)),
      Map.of(),
      0
    );

    Assertions.assertEquals(
      List.of(poly1, poly2),
      profile.postProcessLayerFeatures(Building.LAYER_NAME, 14, List.of(poly1, poly2))
    );
    Assertions.assertEquals(
      List.of(),
      profile.postProcessLayerFeatures(Building.LAYER_NAME, 13, List.of(poly1, poly2))
    );
  }

  /**
   * Imaginarium: an address, a node's or a building's own, is a point in the building layer while the tile is made, as
   * in the housenumber layer (not built).
   */
  @Test
  void testAddressPoint() {
    assertFeatures(14, List.of(Map.of(
      "_layer", "building",
      "_type", "point",
      "_address", true,
      "_minzoom", 14,
      "_buffer", 8d
    ), Map.of("_layer", "housenumber")), process(pointFeature(Map.of(
      "addr:housenumber", "7"
    ))));
    assertFeatures(14, List.of(Map.of(
      "_layer", "building",
      "_type", "polygon"
    ), Map.of(
      "_layer", "building",
      "_type", "point",
      "_address", true
    ), Map.of("_layer", "housenumber")), process(polygonFeature(Map.of(
      "building", "yes",
      "addr:housenumber", "7"
    ))));
  }

  /** Imaginarium: a building or a part with an address point in it, on its edge too, is addressed; the points go. */
  @Test
  void testAddressed() throws GeometryException {
    var addressed = new VectorTile.Feature(Building.LAYER_NAME, 1, VectorTile.encodeGeometry(rectangle(10, 20)),
      Map.of("building", "yes"), 0);
    var edged = new VectorTile.Feature(Building.LAYER_NAME, 2, VectorTile.encodeGeometry(rectangle(30, 40)),
      Map.of("building", "yes"), 0);
    var none = new VectorTile.Feature(Building.LAYER_NAME, 3, VectorTile.encodeGeometry(rectangle(50, 60)),
      Map.of("building", "yes"), 0);
    var entrance = new VectorTile.Feature(Building.LAYER_NAME, VectorTile.NO_FEATURE_ID,
      VectorTile.encodeGeometry(GeoUtils.point(15, 15)), Map.of("entrance", "yes"), 0);
    List<VectorTile.Feature> items = new java.util.ArrayList<>(List.of(addressed, edged, none, entrance));
    for (var at : List.of(GeoUtils.point(15, 15), GeoUtils.point(12, 18), GeoUtils.point(30, 35),
      GeoUtils.point(45, 45))) {
      items.add(new VectorTile.Feature(Building.LAYER_NAME, VectorTile.NO_FEATURE_ID, VectorTile.encodeGeometry(at),
        Map.of("_address", true), 0));
    }
    Assertions.assertEquals(List.of(
      Map.of("building", "yes", "addressed", true),
      Map.of("building", "yes", "addressed", true),
      Map.of("building", "yes"),
      Map.of("entrance", "yes")
    ), profile.postProcessLayerFeatures(Building.LAYER_NAME, 14, items).stream().map(VectorTile.Feature::tags)
      .toList());
  }

  /** Imaginarium: no `colour` nor `hide_3d` (nothing reads them), the tagged `building:colour` instead. */
  @Test
  void testNoColourNorHidden() {
    assertFeatures(14, List.of(Map.of(
      "colour", "<null>",
      "hide_3d", "<null>",
      "building:colour", "#ff0000"
    )), process(polygonFeature(Map.of(
      "building", "yes",
      "building:colour", "#ff0000",
      "building:material", "brick"
    ))));
  }
}
