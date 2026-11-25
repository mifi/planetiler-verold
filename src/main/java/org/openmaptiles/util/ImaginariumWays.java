package org.openmaptiles.util;

import com.carrotsearch.hppc.LongHashSet;
import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.geo.GeoUtils;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.reader.osm.FullWay;
import com.onthegomap.planetiler.reader.osm.OsmElement;
import com.onthegomap.planetiler.reader.osm.OsmSourceFeature;
import com.onthegomap.planetiler.reader.osm.SplitWay;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.locationtech.jts.geom.LineString;

/**
 * Imaginarium's tunnel and long-structure index is built from whole ways, not from tiles: this writes, beside the tiles
 * and from the same read of the file, every way of the transportation layer that is a tunnel, a bridge or covered (by
 * its raw tags, so `tunnel=building_passage`, `covered=yes` and `tunnel=culvert` are told apart), and every other such
 * way that shares a node with one of them (where a mouth is), each whole: its id, its node ids and their places, the
 * fields the tiles give it at the near zoom (class, subclass, brunnel, layer, oneway, ...) and the raw tags that say
 * what kind of structure it is. One JSON object a line, gzipped, at {@code --imaginarium-ways=<path>}; nothing when
 * that is not given.
 * <p>
 * The first pass over the file notes the nodes of the structures' ways (8 bytes or so a node, in one set); the second
 * writes the ways as the layers make them, so the fields are the tiles' own.
 */
public final class ImaginariumWays {

  /** The raw tags written, besides the tiles' fields. */
  private static final List<String> RAW = List.of("highway", "railway", "tunnel", "covered", "bridge", "layer", "level",
    "service", "location");

  private final Writer out;
  private final LongHashSet watched = new LongHashSet();
  private long written = 0;

  private ImaginariumWays(Path path) throws IOException {
    out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(path), 1 << 16),
      StandardCharsets.UTF_8), 1 << 20);
  }

  /** The writer `--imaginarium-ways` asks for, or null. */
  public static ImaginariumWays from(PlanetilerConfig config) {
    String path = config.arguments().getString("imaginarium_ways",
      "where to write the tunnel, bridge and covered ways and those sharing a node with them (gzipped JSON lines)", "");
    if (path.isBlank()) {
      return null;
    }
    try {
      return new ImaginariumWays(Path.of(path));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean said(Object value) {
    return value != null && !"".equals(value) && !"no".equals(value);
  }

  /** Whether a way's tags make it a structure: a highway or railway in a tunnel, on a bridge, or covered. */
  private static boolean structure(Map<String, Object> tags) {
    return (tags.containsKey("highway") || tags.containsKey("railway")) &&
      (said(tags.get("tunnel")) || said(tags.get("bridge")) || said(tags.get("covered")));
  }

  /** Pass 1: the nodes of the structures' ways noted (by the pass's threads at once, so under a lock). */
  public void preprocess(OsmElement.Way way) {
    if (structure(way.tags())) {
      synchronized (watched) {
        watched.addAll(way.nodes());
      }
    }
  }

  /** Pass 2: a way written if it is a structure or shares a node with one, with what the layers made of it. */
  public void offer(SourceFeature source, FeatureCollector features) {
    if (!(source instanceof OsmSourceFeature<?> osm) || !(osm.originalElement()instanceof OsmElement.Way way)) {
      return;
    }
    // A way cut at its junctions (Imaginarium.cutAtJunctions) comes whole first, which the layers make nothing of, then
    // in pieces, which they make the lines of: it is written whole, as its first piece's line was made.
    if (source instanceof FullWay) {
      whole.set(source);
      return;
    }
    if (source instanceof SplitWay) {
      var full = whole.get();
      whole.remove();
      if (full == null || full.id() != source.id() ||
        !(((OsmSourceFeature<?>) full).originalElement()instanceof OsmElement.Way fullWay)) {
        return;
      }
      offer(full, fullWay, features);
      return;
    }
    offer(source, way, features);
  }

  /** The whole way last seen on this thread, of a way cut at its junctions, till its first piece is made. */
  private final ThreadLocal<SourceFeature> whole = new ThreadLocal<>();

  private void offer(SourceFeature source, OsmElement.Way way, FeatureCollector features) {
    FeatureCollector.Feature made = null;
    for (var feature : features) {
      if ("transportation".equals(feature.getLayer())) {
        made = feature;
        break;
      }
    }
    if (made == null) {
      return;
    }
    var nodes = way.nodes();
    boolean wanted = structure(way.tags());
    // (Read without a lock: the set is only written in pass 1, which is over before pass 2 starts.)
    for (int at = 0; at < nodes.size() && !wanted; at++) {
      wanted = watched.contains(nodes.get(at));
    }
    if (!wanted) {
      return;
    }
    LineString line;
    try {
      if (!(source.line()instanceof LineString one) || one.getNumPoints() != nodes.size()) {
        return;
      }
      line = one;
    } catch (GeometryException e) {
      return;
    }
    var json = new StringBuilder(64 + nodes.size() * 40);
    json.append("{\"id\":").append(way.id()).append(",\"fields\":{");
    boolean first = true;
    for (var entry : made.getAttrsAtZoom(Imaginarium.NEAR_ZOOM).entrySet()) {
      if (entry.getValue() == null || Imaginarium.OSM_ID.equals(entry.getKey())) {
        continue;
      }
      if (!first) {
        json.append(',');
      }
      first = false;
      quoted(json, entry.getKey()).append(':');
      if (entry.getValue() instanceof Number || entry.getValue() instanceof Boolean) {
        json.append(entry.getValue());
      } else {
        quoted(json, entry.getValue().toString());
      }
    }
    json.append("},\"tags\":{");
    first = true;
    for (String key : RAW) {
      Object value = source.getTag(key);
      if (value == null) {
        continue;
      }
      if (!first) {
        json.append(',');
      }
      first = false;
      quoted(json, key).append(':');
      quoted(json, value.toString());
    }
    json.append("},\"nodes\":[");
    for (int at = 0; at < nodes.size(); at++) {
      json.append(at == 0 ? "" : ",").append(nodes.get(at));
    }
    json.append("],\"places\":[");
    for (int at = 0; at < nodes.size(); at++) {
      var point = line.getCoordinateN(at);
      json.append(at == 0 ? "" : ",")
        .append(Math.round(GeoUtils.getWorldLat(point.y) * 1e7) / 1e7).append(',')
        .append(Math.round(GeoUtils.getWorldLon(point.x) * 1e7) / 1e7);
    }
    json.append("]}\n");
    synchronized (out) {
      try {
        out.write(json.toString());
        written++;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private static StringBuilder quoted(StringBuilder json, String text) {
    json.append('"');
    for (char c : text.toCharArray()) {
      if (c == '"' || c == '\\') {
        json.append('\\').append(c);
      } else if (c < 0x20) {
        json.append(String.format("\\u%04x", (int) c));
      } else {
        json.append(c);
      }
    }
    return json.append('"');
  }

  /** The file closed, once the OpenStreetMap source is done. */
  public void close() {
    synchronized (out) {
      try {
        out.close();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    System.err.println("imaginarium ways: " + written + " written, " + watched.size() + " nodes watched");
  }
}
