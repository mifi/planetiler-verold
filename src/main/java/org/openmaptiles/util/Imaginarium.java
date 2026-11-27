package org.openmaptiles.util;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.expression.Expression;
import com.onthegomap.planetiler.geo.GeoUtils;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.geo.GeometryType;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.reader.osm.OsmElement;
import com.onthegomap.planetiler.reader.osm.OsmSourceFeature;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.locationtech.jts.geom.CoordinateList;
import org.locationtech.jts.geom.LineString;
import org.openmaptiles.generated.Tables;

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
    "building:material", "bridge:support", "man_made", "shop", "office", "amenity", "religion", "building:height",
    "building:min_height");

  /**
   * The counts of storeys a building and a part carry, as numbers (see {@link #numberOf}). `levels` and `min_level`,
   * and of the lengths `building:height` and `building:min_height`, are the keys OpenMapTiles reads a building's height
   * and base of beside `height`, `min_height`, `building:levels` and `building:min_level`, which the app makes them of
   * as it did (its `render_height` and `render_min_height`, no longer carried).
   */
  public static final List<String> BUILDING_COUNTS = List.of("building:levels", "building:min_level", "roof:levels",
    "levels", "min_level");

  /** Which way a roof faces down its slope, as degrees clockwise from north (see {@link #degreesOf}). */
  public static final String ROOF_DIRECTION = "roof:direction";

  /** When a building was built, as the year it says (see {@link #yearOf}). */
  public static final String START_DATE = "start_date";

  /** The colours a building and a part carry, as `#rrggbb` (see {@link #colourOf}). */
  public static final List<String> BUILDING_COLOURS = List.of("building:colour", "roof:colour");

  /**
   * The tags a road carries as tagged (`surface` and `service` are OpenMapTiles' own reduced values; see ROAD_RENAMED).
   */
  public static final List<String> ROAD_TAGS = List.of(
    "lanes", "width", "sidewalk", "lit", "maxheight", "maxspeed", "turn:lanes", "tunnel", "covered", "lanes:forward",
    "lanes:backward", "cycleway", "cycleway:left", "cycleway:right", "cycleway:both", "parking:both", "parking:left",
    "parking:right", "step_count", "handrail");

  /** OpenStreetMap keys whose names OpenMapTiles uses for reduced values: carried as tagged under `<key>:osm`. */
  public static final List<String> ROAD_RENAMED = List.of("surface", "service");

  /** The tags an entrance carries, a point in the building layer. */
  public static final List<String> ENTRANCE_TAGS = List.of("entrance", "door", "level", "wheelchair");

  /**
   * The ways the transportation layer makes its lines of, by their tags alone (its tables' mappings, as pass 1 sees a
   * way, before it has a shape): each is cut by Planetiler at every node it shares with another of them or with itself
   * (`splitOsmWayAtIntersections`), so every junction, crossing and way's end on another's run is a point of both lines
   * in the tiles, which simplifying would otherwise drop where the ways run straight through it. The pieces are made
   * each as the way would be (`splitLine`, numbered in the way's order) and joined again in that order as each tile is
   * written, unsimplified at the near zoom (Transportation `postProcess`), so a way is still one line, from its own
   * first node, with those points kept.
   */
  private static final Expression TRANSPORTATION_LINES = Expression.or(
    Tables.OsmHighwayLinestring.MAPPING, Tables.OsmRailwayLinestring.MAPPING, Tables.OsmAerialwayLinestring.MAPPING,
    Tables.OsmShipwayLinestring.MAPPING).replace(e -> e instanceof Expression.MatchType, Expression.TRUE).simplify();

  /**
   * Whether a way is one the transportation layer makes lines of, cut at the nodes it shares (TRANSPORTATION_LINES):
   * not a closed pier, which it makes a polygon of (Transportation `isPierPolygon`).
   */
  public static boolean cutAtJunctions(OsmElement.Way way) {
    return TRANSPORTATION_LINES.evaluate(way) && !(way.isClosed() && "pier".equals(way.getTag("man_made")));
  }

  /**
   * A tile's lines with each way's pieces (cut at its junctions, see TRANSPORTATION_LINES) joined again in the way's
   * order, end to start, with the points they met at, under the way's own id: one line a way where it runs on, as it
   * was before it was cut, from its own first node (a closed way's too), before the layer's merging. A piece's id is
   * Planetiler's for it (the way's id plus its place along the way times a power of ten, as a vector tile id: × 10 and
   * the element's type), so a way's pieces are in order by their ids, and the way's own is its OSM_ID × 10 and the
   * type.
   */
  public static List<VectorTile.Feature> piecesJoined(List<VectorTile.Feature> items) throws GeometryException {
    Map<Map<String, Object>, List<VectorTile.Feature>> byWay = new LinkedHashMap<>();
    List<VectorTile.Feature> joined = new ArrayList<>(items.size());
    for (var item : items) {
      if (item.tags().get(OSM_ID) instanceof Number && item.geometry().geomType() == GeometryType.LINE) {
        byWay.computeIfAbsent(item.tags(), key -> new ArrayList<>()).add(item);
      } else {
        joined.add(item);
      }
    }
    for (var pieces : byWay.values()) {
      var first = pieces.getFirst();
      long id = ((Number) first.tags().get(OSM_ID)).longValue() * 10 + Math.floorMod(first.id(), 10);
      if (pieces.size() == 1) {
        joined.add(first.id() == id ? first : first.copyWithIdAndGeometry(id, first.geometry()));
        continue;
      }
      pieces.sort(Comparator.comparingLong(VectorTile.Feature::id));
      List<LineString> lines = new ArrayList<>();
      CoordinateList points = null;
      for (var piece : pieces) {
        var geometry = piece.geometry().decode();
        for (int at = 0; at < geometry.getNumGeometries(); at++) {
          var line = (LineString) geometry.getGeometryN(at);
          if (points == null || !points.getLast().equals2D(line.getCoordinateN(0))) {
            lineOf(points, lines);
            points = new CoordinateList();
          }
          points.add(line.getCoordinates(), false);
        }
      }
      lineOf(points, lines);
      if (!lines.isEmpty()) {
        joined.add(first.copyWithIdAndGeometry(id, VectorTile.encodeGeometry(GeoUtils.combineLineStrings(lines))));
      }
    }
    return joined;
  }

  /** Points joined into a line, if they are one (a piece shorter than the tile's grid is a point). */
  private static void lineOf(CoordinateList points, List<LineString> lines) {
    if (points != null && points.size() >= 2) {
      lines.add(GeoUtils.JTS_FACTORY.createLineString(points.toCoordinateArray()));
    }
  }

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
   * The subclasses of landcover kept: the parks the app reads (its vector/leisure.ts PARKS), and, carried for later,
   * orchards, vineyards, allotments, beaches and sand, which ESA WorldCover does not tell from the land about them; the
   * rest, the woods, grass, farmland, rock and ice, it has from WorldCover, not the tiles.
   */
  public static final Set<String> LANDCOVER_KEPT = Set.of("park", "village_green", "recreation_ground", "orchard",
    "vineyard", "allotments", "beach", "sand");

  /**
   * A kind of thing mapped as a point or a line that the world can stand up: what carries `key` with one of `values`
   * (any but `no` where none are listed) and the `with` tags too; written by its key as `class` and its value as tagged
   * as `subclass`, with its `tags` as tagged.
   */
  public record Standing(String key, Set<String> values, Map<String, String> with, List<String> tags) {

    Standing(String key, Set<String> values, List<String> tags) {
      this(key, values, Map.of(), tags);
    }

    /** Whether a feature is one of these. */
    public boolean of(SourceFeature source) {
      Object value = source.getTag(key);
      if (value == null || "".equals(value) || "no".equals(value) ||
        !(values.isEmpty() || values.contains(value.toString()))) {
        return false;
      }
      for (var tag : with.entrySet()) {
        if (!source.hasTag(tag.getKey(), tag.getValue())) {
          return false;
        }
      }
      return true;
    }
  }

  /**
   * The things mapped as points that the world can stand up, each in the poi layer at the near zoom (see
   * {@link Standing}: `highway` `street_lamp`, `natural` `tree`, `man_made` `tower`), carried STANDING_BUFFER past a
   * tile's edge. In order: a node tagged as two of them is the first.
   */
  public static final List<Standing> STANDING = List.of(
    new Standing("highway", Set.of("street_lamp", "crossing", "traffic_signals", "stop", "give_way"),
      List.of("crossing")),
    new Standing("amenity", Set.of("bench", "vending_machine"), List.of("vending")),
    new Standing("leisure", Set.of("picnic_table"), List.of()),
    new Standing("natural", Set.of("tree"), List.of("leaf_type", "genus")),
    new Standing("railway", Set.of("level_crossing", "signal"), List.of()),
    new Standing("traffic_calming", Set.of(), List.of()),
    new Standing("emergency", Set.of("fire_hydrant"), List.of()),
    new Standing("man_made", Set.of("street_cabinet", "mast", "tower"), List.of("height", "tower:type")),
    new Standing("historic", Set.of("memorial"), List.of("memorial")),
    new Standing("power", Set.of("generator"), Map.of("generator:source", "wind"), List.of("height")));

  /** What a bus stop's point in the poi layer carries beside OpenMapTiles' own fields, as tagged. */
  public static final List<String> STOP_TAGS = List.of("shelter", "bench");

  /**
   * The things mapped as lines that the world can stand up, each in the structure layer at the near zoom (see
   * {@link Standing}; `org.openmaptiles.addons.Structure`): walls, fences and hedges, power lines on their towers, rows
   * of trees and cliffs.
   */
  public static final List<Standing> LINED = List.of(
    new Standing("barrier", Set.of("wall", "fence", "hedge", "retaining_wall", "guard_rail"), List.of()),
    new Standing("power", Set.of("line"), List.of()),
    new Standing("natural", Set.of("tree_row", "cliff"), List.of()));

  /** The one of a list a feature is, the first; none where it is none of them. */
  public static Standing standing(List<Standing> standing, SourceFeature source) {
    for (var each : standing) {
      if (each.of(source)) {
        return each;
      }
    }
    return null;
  }

  /** Says what a feature stands as on what is made of it: its key as `class`, its value `subclass`, its tags. */
  public static void stands(Standing standing, SourceFeature source, FeatureCollector.Feature feature) {
    feature.setBufferPixels(STANDING_BUFFER).setMinZoom(NEAR_ZOOM)
      .setAttr("class", standing.key())
      .setAttr("subclass", source.getString(standing.key()));
    for (String key : standing.tags()) {
      feature.setAttr(key, Utils.nullIfEmpty(source.getString(key)));
    }
  }

  /**
   * How far past its edge a tile carries what stands (STANDING, LINED), z14 pixels: a building's margin, not the poi
   * layer's 64 for its labels, which would carry each into two or more tiles.
   */
  public static final double STANDING_BUFFER = 4;

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
    feature.setAttrWithMinzoom(START_DATE, yearOf(source.getTag(START_DATE)), NEAR_ZOOM);
    for (String key : BUILDING_COLOURS) {
      feature.setAttrWithMinzoom(key, colourOf(source.getTag(key)), NEAR_ZOOM);
    }
  }

  /** A whole number as a long, so the tile carries it as an integer, and any other as a double. */
  private static Number compact(double value) {
    return value == Math.rint(value) && Math.abs(value) < 1e15 ? (Number) (long) value : (Number) value;
  }

  private static final Pattern YEAR = Pattern.compile("(?<!\\d)(\\d{3,4})(?!\\d)");

  /**
   * The year a date says, the first number of three or four digits in it (`1923`, `1923-05-01`, `~1920`, `1920s`,
   * `before 1900`), or none where it has none or it is after 2100 (`19th century`, `C19`).
   */
  public static Long yearOf(Object value) {
    if (value == null) {
      return null;
    }
    Matcher matcher = YEAR.matcher(value.toString());
    if (!matcher.find()) {
      return null;
    }
    long year = Long.parseLong(matcher.group(1));
    return year <= 2100 ? year : null;
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
