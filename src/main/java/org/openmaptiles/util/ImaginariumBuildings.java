package org.openmaptiles.util;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.geo.GeometryType;
import com.onthegomap.planetiler.geo.TileCoord;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.stats.Stats;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

/**
 * Imaginarium: every building whole, in one tile, so the reader builds each once from all of it and never puts it
 * together again from pieces cut at tiles' edges.
 * <p>
 * A building's home is the z14 tile its home point is in: the middle of its outline's corners, which does not move with
 * how it is cut, and is what the app gives a building to the block of its world by (its `middleOf`), so the block a
 * building is drawn by always reads the tile it is at home in. The building is made with a buffer as wide as it is, so
 * the tile it is at home in holds all of it, uncut; and of the other tiles holding some of it, those it reaches into
 * their usual margin keep it too, marked {@link #ELSEWHERE} (what a reader asks of its neighbours: which shares a wall
 * with which), and the rest drop it.
 * <p>
 * A building part is at home where its building is, so the two always arrive in one tile, and names it
 * ({@link #PARENT}, the building's own feature id): its building is the outline round a point inside the part
 * ({@link Geometry#getInteriorPoint}), of those the tile holds, the smallest. Its buffer is {@link #PART_REACH} or its
 * own size, whichever is more, so the building's home tile holds all of it for a building no bigger than that; a part
 * of a bigger one (and a part with no building round it) is at home where its own home point is, still whole, and its
 * parent there or not. Nothing is ever cut.
 * <p>
 * A tile holding the same as the one before it is written as that one was (Planetiler's own shortcut), which holds here
 * too: what a tile keeps depends on where it is only through what is at home in it, which it holds whole, and so never
 * the same as the tile before it does.
 */
public final class ImaginariumBuildings {

  private ImaginariumBuildings() {}

  /** The building layer. */
  public static final String LAYER = "building";
  /** On a building part: the feature id of the building outline it is a part of. */
  public static final String PARENT = "parent";
  /** On a building in a tile that is not its home: there for its neighbours, built by the tile it is at home in. */
  public static final String ELSEWHERE = "elsewhere";
  /** What says a building is a part. */
  private static final String PART = "building:part";

  /**
   * How far a part is carried past each tile's edge, z14 pixels (256 a tile): some 600 m in Norway, 1.2 km at the
   * equator.
   */
  public static final double PART_REACH = 128;
  /** How far past its edge a tile holds what is elsewhere, z14 pixels: OpenMapTiles' building buffer. */
  public static final double MARGIN = 4;

  /**
   * Where a building's home point is, in z14 pixels over the world, how big it is (its box's longer side), and of a
   * part a point inside it.
   */
  private static final String HOME_X = "_home_x";
  private static final String HOME_Y = "_home_y";
  private static final String SIZE = "_size";
  private static final String INSIDE_X = "_inside_x";
  private static final String INSIDE_Y = "_inside_y";

  private static final double WORLD = 256d * (1 << Imaginarium.NEAR_ZOOM);
  private static final GeometryFactory GEOMETRY = new GeometryFactory();

  /** The archive's metadata field saying its tiles have whole buildings (see the app's docs/own-tiles.md). */
  public static final String METADATA_KEY = "imaginarium:buildings";
  public static final String METADATA_VALUE = "whole";

  /**
   * A building or a part made whole: its buffer as big as it is (a part's at least PART_REACH), and its home point and
   * size noted for the tiles to sort out (see {@link #homed}), and of a part a point inside it.
   */
  public static void whole(FeatureCollector.Feature feature, SourceFeature source, boolean part) {
    try {
      Geometry world = source.worldGeometry();
      Envelope box = world.getEnvelopeInternal();
      double size = Math.max(box.getWidth(), box.getHeight()) * WORLD;
      Coordinate home = middleOf(world);
      if (home == null) {
        return;
      }
      feature
        .setBufferPixels(Math.max(part ? PART_REACH : MARGIN, Math.ceil(size) + 1))
        .setAttrWithMinzoom(HOME_X, home.getX() * WORLD, Imaginarium.NEAR_ZOOM)
        .setAttrWithMinzoom(HOME_Y, home.getY() * WORLD, Imaginarium.NEAR_ZOOM)
        .setAttrWithMinzoom(SIZE, size, Imaginarium.NEAR_ZOOM);
      if (part) {
        Point inside = world.getInteriorPoint();
        feature
          .setAttrWithMinzoom(INSIDE_X, inside.isEmpty() ? null : inside.getX() * WORLD, Imaginarium.NEAR_ZOOM)
          .setAttrWithMinzoom(INSIDE_Y, inside.isEmpty() ? null : inside.getY() * WORLD, Imaginarium.NEAR_ZOOM);
      }
    } catch (GeometryException e) {
      // Left as OpenMapTiles has it: cut at the margin, in every tile it reaches (see `homed`).
    }
  }

  /** The middle of the corners of an outline's outer rings, each corner once (not the ring's closing repeat). */
  public static Coordinate middleOf(Geometry outline) {
    double x = 0;
    double y = 0;
    int corners = 0;
    for (int at = 0; at < outline.getNumGeometries(); at++) {
      if (outline.getGeometryN(at)instanceof Polygon polygon) {
        var ring = polygon.getExteriorRing().getCoordinates();
        for (int corner = 0; corner + 1 < ring.length; corner++) {
          x += ring[corner].getX();
          y += ring[corner].getY();
          corners++;
        }
      }
    }
    return corners == 0 ? null : new Coordinate(x / corners, y / corners);
  }

  private record Held(VectorTile.Feature feature, double x, double y, double size, Geometry geometry) {}

  private static double number(VectorTile.Feature feature, String key) {
    return feature.tags().get(key)instanceof Number number ? number.doubleValue() : Double.NaN;
  }

  /**
   * A z14 tile's buildings as each tile keeps them: each at home here whole, with its parts naming it; each at home
   * elsewhere that reaches this tile's margin, marked so (a part only where it has no building to go with); nothing
   * else.
   */
  public static List<VectorTile.Feature> homed(TileCoord tile, List<VectorTile.Feature> items, Stats stats)
    throws GeometryException {
    if (tile.z() != Imaginarium.NEAR_ZOOM) {
      return items;
    }
    double left = tile.x() * 256d;
    double top = tile.y() * 256d;
    List<Held> outlines = new ArrayList<>();
    List<VectorTile.Feature> parts = new ArrayList<>();
    List<VectorTile.Feature> kept = new ArrayList<>(items.size());
    for (var item : items) {
      if (item.geometry().geomType() != GeometryType.POLYGON || !item.tags().containsKey(HOME_X)) {
        kept.add(item);
      } else if (item.tags().containsKey(PART)) {
        parts.add(item);
      } else {
        outlines.add(new Held(item, number(item, HOME_X), number(item, HOME_Y), number(item, SIZE), null));
      }
    }
    // Which outlines' geometry is wanted: only where there are parts to find their buildings of.
    if (!parts.isEmpty()) {
      for (int at = 0; at < outlines.size(); at++) {
        var outline = outlines.get(at);
        outlines.set(at,
          new Held(outline.feature, outline.x, outline.y, outline.size, outline.feature.geometry().decode()));
      }
    }
    for (var outline : outlines) {
      keep(kept, outline.feature, here(tile, outline.x, outline.y), left, top);
    }
    for (var part : parts) {
      double x = number(part, HOME_X);
      double y = number(part, HOME_Y);
      double size = number(part, SIZE);
      double insideX = number(part, INSIDE_X);
      double insideY = number(part, INSIDE_Y);
      // Its building: the outline round a point inside it, the smallest of two (then the lesser id), as every tile
      // holding both says alike.
      Point point = GEOMETRY.createPoint(new Coordinate(insideX - left, insideY - top));
      Held parent = null;
      for (var outline : outlines) {
        if (outline.geometry.getEnvelopeInternal().contains(point.getCoordinate()) &&
          outline.geometry.contains(point) &&
          (parent == null || outline.size < parent.size ||
            (outline.size == parent.size && outline.feature.id() < parent.feature.id()))) {
          parent = outline;
        }
      }
      if (parent != null && parent.size + size <= PART_REACH) {
        // Wherever its building is at home, all of it there: it lies within its building's size and its own of it.
        if (here(tile, parent.x, parent.y)) {
          kept.add(unmarked(part).copyWithExtraAttrs(java.util.Map.of(PARENT, parent.feature.id())));
        }
        continue;
      }
      boolean home = here(tile, x, y);
      if (home) {
        stats.dataError(parent == null ? "imaginarium_part_without_building" : "imaginarium_part_away_from_building");
      }
      if (parent == null) {
        keep(kept, part, home, left, top);
      } else if (home) {
        kept.add(unmarked(part).copyWithExtraAttrs(java.util.Map.of(PARENT, parent.feature.id())));
      }
    }
    return kept;
  }

  /** Whether a home point is on this tile. */
  private static boolean here(TileCoord tile, double x, double y) {
    return Math.floor(x / 256) == tile.x() && Math.floor(y / 256) == tile.y();
  }

  /**
   * A building kept here: as it is where it is at home here, marked ELSEWHERE where it reaches the margin, else not.
   */
  private static void keep(List<VectorTile.Feature> kept, VectorTile.Feature item, boolean home, double left,
    double top) throws GeometryException {
    if (home) {
      kept.add(unmarked(item));
      return;
    }
    Envelope box = item.geometry().decode().getEnvelopeInternal();
    if (box.getMaxX() >= -MARGIN && box.getMinX() <= 256 + MARGIN && box.getMaxY() >= -MARGIN &&
      box.getMinY() <= 256 + MARGIN) {
      kept.add(unmarked(item).copyWithExtraAttrs(java.util.Map.of(ELSEWHERE, true)));
    }
  }

  /** A building without its home point and size, which only the tiles sorting it out want. */
  private static VectorTile.Feature unmarked(VectorTile.Feature item) {
    item.tags().remove(HOME_X);
    item.tags().remove(HOME_Y);
    item.tags().remove(SIZE);
    item.tags().remove(INSIDE_X);
    item.tags().remove(INSIDE_Y);
    return item;
  }
}
