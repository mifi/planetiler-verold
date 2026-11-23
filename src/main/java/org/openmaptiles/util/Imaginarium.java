package org.openmaptiles.util;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.reader.osm.OsmElement;
import com.onthegomap.planetiler.reader.osm.OsmSourceFeature;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

  /**
   * The tags a building and a building part carry as tagged. Lengths (`height`, `min_height`, `roof:height`) stay as
   * tagged, in whatever unit the mapper wrote: the reader parses them. Counts, the roof's direction and the colours are
   * normalised instead (see BUILDING_COUNTS, ROOF_DIRECTION, BUILDING_COLOURS).
   */
  public static final List<String> BUILDING_TAGS = List.of(
    "building", "building:part", "height", "min_height", "roof:shape", "roof:height", "roof:material",
    "building:material", "bridge:support", "man_made", "shop", "office", "amenity", "religion");

  /** The counts of storeys a building and a part carry, as numbers (see {@link #numberOf}). */
  public static final List<String> BUILDING_COUNTS = List.of("building:levels", "building:min_level", "roof:levels");

  /** Which way a roof faces down its slope, as degrees clockwise from north (see {@link #degreesOf}). */
  public static final String ROOF_DIRECTION = "roof:direction";

  /** The colours a building and a part carry, as `#rrggbb` (see {@link #colourOf}). */
  public static final List<String> BUILDING_COLOURS = List.of("building:colour", "roof:colour");

  /**
   * The tags a road carries as tagged (`surface` and `service` are OpenMapTiles' own reduced values; see ROAD_RENAMED).
   */
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

  /**
   * Puts a building's or a building part's id and fields on it at the near zoom: BUILDING_TAGS as tagged, and the
   * counts, the roof's direction and the colours normalised, each left out where it is not one.
   */
  public static void building(FeatureCollector.Feature feature, SourceFeature source) {
    tagged(feature, source, BUILDING_TAGS);
    for (String key : BUILDING_COUNTS) {
      feature.setAttrWithMinzoom(key, numberOf(source.getTag(key)), NEAR_ZOOM);
    }
    feature.setAttrWithMinzoom(ROOF_DIRECTION, degreesOf(source.getTag(ROOF_DIRECTION)), NEAR_ZOOM);
    for (String key : BUILDING_COLOURS) {
      feature.setAttrWithMinzoom(key, colourOf(source.getTag(key)), NEAR_ZOOM);
    }
  }

  /** A whole number as a long, so the tile carries it as an integer, and any other as a double. */
  private static Number compact(double value) {
    return value == Math.rint(value) && Math.abs(value) < 1e15 ? (Number) (long) value : (Number) value;
  }

  private static final Pattern NUMBER = Pattern.compile("-?(\\d+\\.?\\d*|\\.\\d+)");

  /**
   * A number as it is typed (`3`, `2.5`, a decimal comma's `2,5`), or null for anything else (`3;4`, `yes`, `3 m`).
   */
  public static Number numberOf(Object value) {
    if (value == null) {
      return null;
    }
    String said = value.toString().trim().replaceFirst("^(-?\\d+),(\\d+)$", "$1.$2");
    if (!NUMBER.matcher(said).matches()) {
      return null;
    }
    double number = Double.parseDouble(said);
    return Double.isFinite(number) ? compact(number) : null;
  }

  /** The points of the compass, each 22.5 degrees clockwise from the one before, from north. */
  private static final List<String> COMPASS = List.of(
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW");

  /**
   * A direction in degrees clockwise from north, in [0, 360): a number of degrees or a point of the compass (`N`,
   * `NNE`, ...); null for anything else.
   */
  public static Number degreesOf(Object value) {
    if (value == null) {
      return null;
    }
    int point = COMPASS.indexOf(value.toString().trim().toUpperCase(Locale.ROOT));
    if (point >= 0) {
      return compact(point * 22.5);
    }
    Number degrees = numberOf(value);
    if (degrees == null) {
      return null;
    }
    double turned = degrees.doubleValue();
    // (Only one out of range is wrapped: wrapping one in range would cost it its last digits.)
    return turned >= 0 && turned < 360 ? degrees : compact(((turned % 360) + 360) % 360);
  }

  private static final Pattern HEX = Pattern.compile("#?([0-9a-f]{3}|[0-9a-f]{6})");

  /**
   * A colour as `#rrggbb`, lower case: from `#rgb` or `#rrggbb` (the `#` optional), or a colour's name (see
   * ImaginariumColours, spaces, `_` and `-` ignored); null for anything else.
   */
  public static String colourOf(Object value) {
    if (value == null) {
      return null;
    }
    String said = value.toString().trim().toLowerCase(Locale.ROOT);
    Matcher hex = HEX.matcher(said);
    if (hex.matches()) {
      String digits = hex.group(1);
      return "#" + (digits.length() == 3 ?
        digits.replaceAll("(.)", "$1$1") :
        digits);
    }
    return ImaginariumColours.NAMED.get(said.replaceAll("[\\s_-]", ""));
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
   * Keeps a feature from being made at all at the zooms it is not wanted at, which saves making it and throwing it away
   * (see `kept`): a layer the far zoom has none of starts at the near zoom, and one it keeps at the far zoom.
   */
  public static void slimmed(FeatureCollector features) {
    for (var feature : features) {
      // (One that ends below the near zoom, Natural Earth's, is left as it is for `kept` and --minzoom to drop.)
      if (feature.getMinZoom() >= NEAR_ZOOM || feature.getMaxZoom() < NEAR_ZOOM) {
        continue;
      }
      feature
        .setMinZoom(FAR_LAYERS.contains(feature.getLayer()) ? Math.max(feature.getMinZoom(), FAR_ZOOM) : NEAR_ZOOM);
    }
  }

  /**
   * What of a layer's features at a zoom is written: everything at the near zoom, what FAR_LAYERS says at the far,
   * nothing else.
   */
  public static List<VectorTile.Feature> kept(String layer, int zoom, List<VectorTile.Feature> items) {
    if (zoom == NEAR_ZOOM) {
      return items;
    }
    if (zoom != FAR_ZOOM || !FAR_LAYERS.contains(layer)) {
      return List.of();
    }
    return "transportation".equals(layer) ?
      items.stream()
        .filter(item -> item.tags().get("brunnel")instanceof String brunnel && FAR_BRUNNELS.contains(brunnel))
        .toList() :
      items;
  }
}
