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
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.TopologyException;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.overlayng.OverlayNG;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;

/**
 * Imaginarium: every building whole, in one tile, so the reader builds each once from all of it and never puts it
 * together again from pieces cut at tiles' edges.
 * <p>
 * A building's home is the z14 tile its home point is in: the middle of its outline's corners, which does not move with
 * how it is cut, and is what the app gives a building to the block of its world by (its `middleOf`), so the block a
 * building is drawn by always reads the tile it is at home in. The building is made with a buffer as wide as it is, so
 * the tile it is at home in holds all of it, uncut; and of the other tiles holding some of it, those it reaches into
 * their usual margin, or comes within it of what they build, keep it too, marked {@link #ELSEWHERE} (what a reader asks
 * of its neighbours: which shares a wall with which), and the rest drop it.
 * <p>
 * A building part is at home where its building is, so the two always arrive in one tile, and names it
 * ({@link #PARENT}, the building's own feature id): its building is the outline it shares most with, of those the tile
 * holds. Its buffer is {@link #PART_REACH} or its own size, whichever is more, so the building's home tile holds all of
 * it for a building no bigger than that; a part of a bigger one (and a part with no building under it) is at home where
 * its own home point is, still whole, and its parent there or not. And what lies across what, at home in one tile, is
 * one building (a building inside another, two drawn into each other, a shelter under another's part), named by the
 * biggest outline in it: the rest are its parts, and name it. Nothing is cut but a building too big to be made whole
 * (see {@link #WHOLE_MOST}), which is marked so.
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
   * The biggest building made whole, z14 pixels (its box's longer side): 16 tiles, some 19 km in Norway. One bigger is
   * an outline drawn wrong more likely than a building, and whole it would ride into every tile of its buffer, some
   * 1,000 of them, while the features are sorted: it is cut instead, as OpenMapTiles cuts every building (see
   * {@link #CUT}).
   */
  public static final double WHOLE_MOST = 16 * 256;
  /**
   * On a building cut at each tile's margin, as OpenMapTiles has it, in every tile it reaches: one too big to make
   * whole (see {@link #WHOLE_MOST}), or one whose geometry would not say where its home is. The app builds of it what
   * is on each tile's own ground, as it does a building of tiles that cut them all.
   */
  public static final String CUT = "cut";

  /**
   * Where a building's home point is, in z14 pixels over the world, and how big it is (its box's longer side).
   */
  private static final String HOME_X = "_home_x";
  private static final String HOME_Y = "_home_y";
  private static final String SIZE = "_size";

  private static final double WORLD = 256d * (1 << Imaginarium.NEAR_ZOOM);

  /** The archive's metadata field saying its tiles have whole buildings (see the app's docs/own-tiles.md). */
  public static final String METADATA_KEY = "imaginarium:buildings";
  public static final String METADATA_VALUE = "whole";

  /**
   * A building or a part made whole: its buffer as big as it is (a part's at least PART_REACH), and its home point and
   * size noted for the tiles to sort out (see {@link #homed}); or, where it cannot be (see {@link #CUT}), marked cut.
   * Whether it was made whole.
   */
  public static boolean whole(FeatureCollector.Feature feature, SourceFeature source, boolean part) {
    try {
      Geometry world = source.worldGeometry();
      Envelope box = world.getEnvelopeInternal();
      double size = Math.max(box.getWidth(), box.getHeight()) * WORLD;
      Coordinate home = middleOf(world);
      if (home == null || size > WHOLE_MOST) {
        feature.setAttrWithMinzoom(CUT, true, Imaginarium.NEAR_ZOOM);
        return false;
      }
      feature
        .setBufferPixels(Math.max(part ? PART_REACH : MARGIN, Math.ceil(size) + 1))
        .setAttrWithMinzoom(HOME_X, home.getX() * WORLD, Imaginarium.NEAR_ZOOM)
        .setAttrWithMinzoom(HOME_Y, home.getY() * WORLD, Imaginarium.NEAR_ZOOM)
        .setAttrWithMinzoom(SIZE, size, Imaginarium.NEAR_ZOOM);
      return true;
    } catch (GeometryException e) {
      // Left as OpenMapTiles has it: cut at the margin, in every tile it reaches (see `homed`).
      feature.setAttrWithMinzoom(CUT, true, Imaginarium.NEAR_ZOOM);
      return false;
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

  /**
   * A building or a part as a tile holds it: where it is at home, its size, its outline in this tile's pixels, its
   * area, whether it is at home here, and whether it is a part.
   */
  private record Held(VectorTile.Feature feature, double x, double y, double size, Geometry geometry, double area,
    boolean home, boolean part) {}

  private static double number(VectorTile.Feature feature, String key) {
    return feature.tags().get(key)instanceof Number number ? number.doubleValue() : Double.NaN;
  }

  /**
   * How wide what two outlines share has to be for them to lie across each other, z14 pixels: some half a metre in
   * Norway, the app's own OVERLAPPING (two of a tile's 4096). Narrower is two neighbours drawn a hand's breadth into
   * each other along their wall.
   */
  public static final double SLIVER = 2 * 256d / 4096;

  /**
   * How much two outlines share where one lies across the other: where they share more than a sliver (see
   * {@link #SLIVER}), or at least half of the smaller of them (a part as thin as a sliver, a pillar, inside its
   * building); else none, and none where the geometry will not say.
   */
  private static double shared(Held one, Held other) {
    Envelope a = one.geometry.getEnvelopeInternal();
    Envelope b = other.geometry.getEnvelopeInternal();
    if (!a.intersects(b)) {
      return 0;
    }
    try {
      Geometry both = OverlayNGRobust.overlay(one.geometry, other.geometry, OverlayNG.INTERSECTION);
      double area = both.getArea();
      return area > 0 && (area >= Math.min(one.area, other.area) / 2 || !both.buffer(-SLIVER / 2).isEmpty()) ? area : 0;
    } catch (TopologyException e) {
      return 0;
    }
  }

  /**
   * A z14 tile's buildings as each tile keeps them: each at home here whole, with its parts naming it; each at home
   * elsewhere that reaches this tile's margin, marked so (a part only where it has no building to go with); nothing
   * else.
   * <p>
   * What lies across what is one building (see {@link #shared}). A part goes with the outline it shares most with (of
   * two alike the smaller, then the lesser id, as every tile holding them says alike), and is at home where that one
   * is; and of what is at home here, what lies across another is of one building with it: named by the biggest outline
   * in it (by the biggest part where there is none), which the rest name, as its parts.
   */
  public static List<VectorTile.Feature> homed(TileCoord tile, List<VectorTile.Feature> items, Stats stats)
    throws GeometryException {
    if (tile.z() != Imaginarium.NEAR_ZOOM) {
      return items;
    }
    List<Held> held = new ArrayList<>();
    List<VectorTile.Feature> kept = new ArrayList<>(items.size());
    for (var item : items) {
      if (item.geometry().geomType() != GeometryType.POLYGON || !item.tags().containsKey(HOME_X)) {
        kept.add(item);
        continue;
      }
      // As the app builds it, its courtyards filled.
      Geometry geometry = filled(item.geometry().decode());
      double x = number(item, HOME_X);
      double y = number(item, HOME_Y);
      held.add(new Held(item, x, y, number(item, SIZE), geometry, geometry.getArea(), here(tile, x, y),
        item.tags().containsKey(PART)));
    }
    STRtree index = new STRtree();
    for (int at = 0; at < held.size(); at++) {
      index.insert(held.get(at).geometry.getEnvelopeInternal(), at);
    }
    // Each part's outline: the one it shares most with.
    int[] parent = new int[held.size()];
    for (int at = 0; at < held.size(); at++) {
      parent[at] = -1;
      var part = held.get(at);
      if (!part.part) {
        continue;
      }
      double most = 0;
      for (Object found : index.query(part.geometry.getEnvelopeInternal())) {
        int other = (Integer) found;
        var outline = held.get(other);
        double share = outline.part ? 0 : shared(part, outline);
        var best = parent[at] < 0 ? null : held.get(parent[at]);
        if (share > 0 && (best == null || share > most || (share == most && (outline.size < best.size ||
          (outline.size == best.size && outline.feature.id() < best.feature.id()))))) {
          parent[at] = other;
          most = share;
        }
      }
    }
    // What is built here: what is at home here, and a part where its outline is, which holds all of it there.
    boolean[] built = new boolean[held.size()];
    for (int at = 0; at < held.size(); at++) {
      built[at] = parent[at] < 0 ? held.get(at).home :
        held.get(parent[at]).home && reaches(held.get(parent[at]), held.get(at));
    }
    // Its buildings: each part with its outline, and what lies across what.
    int[] building = new int[held.size()];
    for (int at = 0; at < building.length; at++) {
      building[at] = at;
    }
    for (int at = 0; at < held.size(); at++) {
      if (!built[at]) {
        continue;
      }
      if (parent[at] >= 0) {
        join(building, held, at, parent[at]);
      }
      for (Object found : index.query(held.get(at).geometry.getEnvelopeInternal())) {
        int other = (Integer) found;
        if (other > at && built[other] && shared(held.get(at), held.get(other)) > 0) {
          join(building, held, at, other);
        }
      }
    }
    for (int at = 0; at < held.size(); at++) {
      var one = held.get(at);
      if (built[at]) {
        var named = held.get(root(building, at));
        kept.add(named == one ? unmarked(one.feature) :
          unmarked(one.feature).copyWithExtraAttrs(java.util.Map.of(PARENT, named.feature.id())));
        if (one.part && parent[at] < 0) {
          stats.dataError("imaginarium_part_without_building");
        }
      } else if (parent[at] < 0 && (inMargin(one) || besideBuilt(one, index, held, built))) {
        kept.add(unmarked(one.feature).copyWithExtraAttrs(java.util.Map.of(ELSEWHERE, true)));
      } else if (one.home && !reaches(held.get(parent[at]), one)) {
        // A part of a building too big to bring it home: whole at its own home, naming its building.
        stats.dataError("imaginarium_part_away_from_building");
        kept.add(unmarked(one.feature).copyWithExtraAttrs(java.util.Map.of(PARENT, held.get(parent[at]).feature.id())));
      }
    }
    return kept;
  }

  /**
   * Whether an outline's home tile holds all of a part of it: the outline's size and the part's within PART_REACH (it
   * lies within its outline's size and its own of it).
   */
  private static boolean reaches(Held outline, Held part) {
    return outline.size + part.size <= PART_REACH;
  }

  /** An outline with its courtyards filled, as the app builds it. */
  private static Geometry filled(Geometry geometry) {
    List<Polygon> shells = new ArrayList<>();
    for (int at = 0; at < geometry.getNumGeometries(); at++) {
      if (geometry.getGeometryN(at)instanceof Polygon polygon) {
        shells.add(geometry.getFactory().createPolygon(polygon.getExteriorRing().getCoordinateSequence()));
      }
    }
    return shells.size() == 1 ? shells.getFirst() :
      geometry.getFactory().createMultiPolygon(shells.toArray(Polygon[]::new));
  }

  /** Two things of one building (see `homed`), named as {@link #bigger} says. */
  private static void join(int[] building, List<Held> held, int one, int other) {
    int first = root(building, one);
    int second = root(building, other);
    if (first != second) {
      boolean named = bigger(held.get(first), held.get(second));
      building[named ? second : first] = named ? first : second;
    }
  }

  /** Of two, whether the first is the one a building of both is named by: an outline before a part, then the bigger. */
  private static boolean bigger(Held one, Held other) {
    if (one.part != other.part) {
      return other.part;
    }
    return one.area != other.area ? one.area > other.area : one.feature.id() < other.feature.id();
  }

  /** Which building one is of (see `homed`): the one it is named by. */
  private static int root(int[] building, int at) {
    int named = at;
    while (building[named] != named) {
      named = building[named];
    }
    building[at] = named;
    return named;
  }

  /** Whether a home point is on this tile. */
  private static boolean here(TileCoord tile, double x, double y) {
    return Math.floor(x / 256) == tile.x() && Math.floor(y / 256) == tile.y();
  }

  /** Whether what is at home elsewhere reaches into this tile's margin (see {@link #MARGIN}). */
  private static boolean inMargin(Held one) {
    Envelope box = one.geometry.getEnvelopeInternal();
    return box.getMaxX() >= -MARGIN && box.getMinX() <= 256 + MARGIN && box.getMaxY() >= -MARGIN &&
      box.getMinY() <= 256 + MARGIN;
  }

  /**
   * Whether what is at home elsewhere comes within the margin of what this tile builds (a building reaching out past
   * its tile's edge and its neighbour beyond), where this tile holds it.
   */
  private static boolean besideBuilt(Held one, STRtree index, List<Held> held, boolean[] built) {
    Envelope reach = new Envelope(one.geometry.getEnvelopeInternal());
    reach.expandBy(MARGIN);
    for (Object found : index.query(reach)) {
      int other = (Integer) found;
      if (built[other] && held.get(other).geometry.getEnvelopeInternal().intersects(reach)) {
        return true;
      }
    }
    return false;
  }

  /** A building without its home point and size, which only the tiles sorting it out want. */
  private static VectorTile.Feature unmarked(VectorTile.Feature item) {
    item.tags().remove(HOME_X);
    item.tags().remove(HOME_Y);
    item.tags().remove(SIZE);
    return item;
  }
}
