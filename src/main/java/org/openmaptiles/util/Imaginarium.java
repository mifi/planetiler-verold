package org.openmaptiles.util;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.geo.GeometryType;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.reader.osm.OsmElement;
import com.onthegomap.planetiler.reader.osm.OsmSourceFeature;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
  /** The far zoom: water, runways and helipads. */
  public static final int FAR_ZOOM = 12;

  /**
   * The OpenStreetMap id of what a feature was made of, a node's or a way's, a relation's negated: carried while the
   * features are made and merged, so no two ways merge into one, and taken off as each tile is written (see
   * {@link #trimmed}). The tile says it as each feature's own id instead, which Planetiler writes as the id × 10 + 1, 2
   * or 3 for a node, a way or a relation.
   */
  public static final String OSM_ID = "_osm_id";

  /** The layers the far zoom keeps. */
  private static final Set<String> FAR_LAYERS = Set.of("water", "aeroway");

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

  /**
   * The layers whose features keep their own ids, which the app reads: a building's (outlines and parts; a part names
   * its outline's, `parent`) and a transportation way's (a tunnel's way, which the tunnel index names). An entrance, a
   * point of the building layer, keeps none: nothing reads it.
   */
  private static final Set<String> WITH_IDS = Set.of("building", "transportation");

  /** The fields of each layer nothing in the app reads, beside the names (see {@link #unread}). */
  private static final Map<String, Set<String>> UNREAD = Map.of(
    "poi", Set.of("rank", "indoor"),
    "housenumber", Set.of("housenumber"),
    "water", Set.of("id"),
    "transportation", Set.of("access", "bicycle", "foot", "horse", "mtb_scale", "official", "ramp", "toll", "indoor"));

  /** The poi class whose `name` is read: a fuel or charging station's, for its brand (the app's forecourts.ts). */
  private static final String NAMED_POI = "fuel";

  /**
   * Whether a field of a layer's feature is one nothing reads, and so not written: the id carried while the features
   * are made (see OSM_ID), UNREAD, a poi's names but a fuel station's `name`, and a waterway's names.
   */
  private static boolean unread(String layer, String key, Map<String, Object> tags) {
    if (OSM_ID.equals(key) || UNREAD.getOrDefault(layer, Set.of()).contains(key)) {
      return true;
    }
    boolean name = "name".equals(key) || key.startsWith("name:") || key.startsWith("name_");
    return switch (layer) {
      case "poi" -> name && !("name".equals(key) && NAMED_POI.equals(tags.get("class")));
      case "waterway" -> name;
      default -> false;
    };
  }

  /**
   * What a tile's layer is written with: each feature without what nothing reads (see {@link #unread}), and without its
   * own id but where the app reads it (see WITH_IDS).
   */
  public static List<VectorTile.Feature> trimmed(String layer, List<VectorTile.Feature> items) {
    boolean ids = WITH_IDS.contains(layer);
    List<VectorTile.Feature> trimmed = new ArrayList<>(items.size());
    for (var item : items) {
      boolean idKept = item.id() == VectorTile.NO_FEATURE_ID ||
        (ids && !("building".equals(layer) && item.geometry().geomType() == GeometryType.POINT));
      boolean unread = false;
      for (var key : item.tags().keySet()) {
        unread |= unread(layer, key, item.tags());
      }
      if (idKept && !unread) {
        trimmed.add(item);
        continue;
      }
      Map<String, Object> tags = new HashMap<>(item.tags().size());
      item.tags().forEach((key, value) -> {
        if (!unread(layer, key, item.tags())) {
          tags.put(key, value);
        }
      });
      trimmed.add(new VectorTile.Feature(item.layer(), idKept ? item.id() : VectorTile.NO_FEATURE_ID, item.geometry(),
        tags, item.group()));
    }
    return trimmed;
  }

  /**
   * The subclasses of landcover the app reads, its parks (its vector/leisure.ts PARKS): the rest, the woods, grass,
   * farmland, rock and ice, it has from ESA WorldCover, not the tiles.
   */
  public static final Set<String> LANDCOVER_KEPT = Set.of("park", "village_green", "recreation_ground");

  /**
   * The points of street furniture and trees the world can stand up, each in the poi layer by its key's value as both
   * its class and its subclass (`street_lamp`, `bench`, `picnic_table`, `tree`), at the near zoom; a tree with its
   * `leaf_type` and `genus` as tagged.
   */
  public static final Map<String, String> FURNITURE = furniture();

  /** FURNITURE in a fixed order, so a node tagged as two of them is the same one in every build: the first. */
  private static Map<String, String> furniture() {
    var furniture = new java.util.LinkedHashMap<String, String>();
    furniture.put("street_lamp", "highway");
    furniture.put("bench", "amenity");
    furniture.put("picnic_table", "leisure");
    furniture.put("tree", "natural");
    return java.util.Collections.unmodifiableMap(furniture);
  }

  public static final List<String> TREE_TAGS = List.of("leaf_type", "genus");
  /**
   * How far past its edge a tile carries the furniture and the trees, z14 pixels: a building's margin, not the poi
   * layer's 64 for its labels, which would carry each into two or more tiles.
   */
  public static final double FURNITURE_BUFFER = 4;

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
    return zoom == FAR_ZOOM && FAR_LAYERS.contains(layer) ? items : List.of();
  }
}
