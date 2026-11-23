package org.openmaptiles.util;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.reader.osm.OsmElement;
import com.onthegomap.planetiler.reader.osm.OsmSourceFeature;
import java.util.List;
import java.util.Set;

/**
 * What Imaginarium's tiles change of OpenMapTiles: two zooms only, the near one (z14) with everything and the far one
 * (z12) slim, and the OpenStreetMap ids and raw tags OpenMapTiles leaves out, on the layers they belong to.
 * <p>
 * Layer and field names stay OpenMapTiles'; new fields are named by their OpenStreetMap key and carry its value as
 * tagged, only at the near zoom.
 */
public final class Imaginarium {

  private Imaginarium() {}

  /** The zoom everything is read at near the eye. */
  public static final int NEAR_ZOOM = 14;
  /** The far zoom: water, runways and helipads, and the tunnel and bridge lines the tunnel index follows. */
  public static final int FAR_ZOOM = 12;

  /** The OpenStreetMap id of what a feature was made of: a node's or a way's, a relation's negated. */
  public static final String OSM_ID = "osm_id";

  /** The layers the far zoom keeps (of transportation only the tunnels and bridges). */
  private static final Set<String> FAR_LAYERS = Set.of("water", "aeroway", "transportation");
  private static final Set<String> FAR_BRUNNELS = Set.of("tunnel", "bridge");

  /** The tags a building and a building part carry as tagged. */
  public static final List<String> BUILDING_TAGS = List.of(
    "building", "building:part", "building:levels", "building:min_level", "roof:levels", "height", "min_height",
    "roof:shape", "roof:height", "roof:direction", "roof:orientation", "roof:colour", "roof:material",
    "building:colour", "building:material", "bridge:support", "man_made", "shop", "office", "amenity");

  /** The tags a road carries as tagged (`surface` and `service` are OpenMapTiles' own reduced values; see ROAD_RENAMED). */
  public static final List<String> ROAD_TAGS = List.of(
    "lanes", "width", "sidewalk", "lit", "maxheight", "maxspeed", "turn:lanes", "tunnel", "covered");

  /** OpenStreetMap keys whose names OpenMapTiles uses for reduced values: carried as tagged under `<key>:osm`. */
  public static final List<String> ROAD_RENAMED = List.of("surface", "service");

  /** The tags an entrance carries, a point in the building layer. */
  public static final List<String> ENTRANCE_TAGS = List.of("entrance", "door", "level", "wheelchair");

  /** The OpenStreetMap id of a source feature (see OSM_ID). */
  public static long osmId(SourceFeature source) {
    return source instanceof OsmSourceFeature<?> osm && osm.originalElement() instanceof OsmElement.Relation ?
      -source.id() : source.id();
  }

  /** Puts the id and these tags, as tagged, on a feature at the near zoom. */
  public static void tagged(FeatureCollector.Feature feature, SourceFeature source, List<String> keys) {
    feature.setAttrWithMinzoom(OSM_ID, osmId(source), NEAR_ZOOM);
    for (String key : keys) {
      Object value = source.getTag(key);
      if (value != null && !"".equals(value)) {
        feature.setAttrWithMinzoom(key, value.toString(), NEAR_ZOOM);
      }
    }
  }

  /** Puts these tags on a feature as tagged under `<key>:osm`, at the near zoom. */
  public static void taggedRenamed(FeatureCollector.Feature feature, SourceFeature source, List<String> keys) {
    for (String key : keys) {
      Object value = source.getTag(key);
      if (value != null && !"".equals(value)) {
        feature.setAttrWithMinzoom(key + ":osm", value.toString(), NEAR_ZOOM);
      }
    }
  }

  /**
   * Keeps a feature from being made at all at the zooms it is not wanted at, which saves making it and throwing it
   * away (see `kept`): a layer the far zoom has none of starts at the near zoom, and one it keeps at the far zoom.
   */
  public static void slimmed(FeatureCollector features) {
    for (var feature : features) {
      // (One that ends below the near zoom, Natural Earth's, is left as it is for `kept` and --minzoom to drop.)
      if (feature.getMinZoom() >= NEAR_ZOOM || feature.getMaxZoom() < NEAR_ZOOM) {
        continue;
      }
      feature.setMinZoom(FAR_LAYERS.contains(feature.getLayer()) ? Math.max(feature.getMinZoom(), FAR_ZOOM) : NEAR_ZOOM);
    }
  }

  /** What of a layer's features at a zoom is written: everything at the near zoom, what FAR_LAYERS says at the far, nothing else. */
  public static List<VectorTile.Feature> kept(String layer, int zoom, List<VectorTile.Feature> items) {
    if (zoom == NEAR_ZOOM) {
      return items;
    }
    if (zoom != FAR_ZOOM || !FAR_LAYERS.contains(layer)) {
      return List.of();
    }
    return "transportation".equals(layer) ?
      items.stream().filter(item -> item.tags().get("brunnel") instanceof String brunnel && FAR_BRUNNELS.contains(brunnel)).toList() :
      items;
  }
}
