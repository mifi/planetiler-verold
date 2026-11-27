/*
Copyright (c) 2023, MapTiler.com & OpenMapTiles contributors.
All rights reserved.

Code license: BSD 3-Clause License

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of the copyright holder nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Design license: CC-BY 4.0

See https://github.com/openmaptiles/openmaptiles/blob/master/LICENSE.md for details on usage
*/
package org.openmaptiles.layers;

import static com.onthegomap.planetiler.util.Parse.parseDoubleOrNull;
import static org.openmaptiles.util.Utils.coalesce;
import static org.openmaptiles.util.Utils.nullIfEmpty;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.FeatureMerge;
import com.onthegomap.planetiler.ForwardingProfile;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.expression.Expression;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.stats.Stats;
import com.onthegomap.planetiler.util.Parse;
import com.onthegomap.planetiler.util.Translations;
import java.util.List;
import org.openmaptiles.OpenMapTilesProfile;
import org.openmaptiles.generated.OpenMapTilesSchema;
import org.openmaptiles.generated.Tables;
import org.openmaptiles.util.Imaginarium;
import org.openmaptiles.util.ImaginariumBuildings;

/**
 * Defines the logic for generating map elements for buildings in the {@code building} layer from source features.
 * <p>
 * This class is ported to Java from
 * <a href="https://github.com/openmaptiles/openmaptiles/tree/master/layers/building">OpenMapTiles building sql
 * files</a>.
 */
public class Building implements
  OpenMapTilesSchema.Building,
  Tables.OsmBuildingPolygon.Handler,
  ForwardingProfile.LayerPostProcessor,
  OpenMapTilesProfile.OsmAllProcessor {

  /*
   * Emit all buildings from OSM data at z14.
   *
   * At z13, emit all buildings at process-time, but then at tile render-time,
   * merge buildings that are overlapping or almost touching into combined
   * buildings so that entire city blocks show up as a single building polygon.
   *
   * THIS IS VERY EXPENSIVE! Merging buildings at z13 adds about 50% to the
   * total map generation time.  To disable it, set building_merge_z13 argument
   * to false.
   *
   * Imaginarium: OpenMapTiles' `colour` (of `building:colour` or its material) and `hide_3d` (of an outline drawn round
   * its parts) are not made: nothing reads them, the app reads `building:colour` and the parts as tagged.
   */
  private final boolean mergeZ13Buildings;

  /** Imaginarium: where the buildings too big to make whole are counted (see ImaginariumBuildings#WHOLE_MOST). */
  private final Stats stats;

  public Building(Translations translations, PlanetilerConfig config, Stats stats) {
    this.stats = stats;
    this.mergeZ13Buildings = config.arguments().getBoolean(
      "building_merge_z13",
      "building: merge nearby buildings at z13",
      false
    );
  }

  /** Imaginarium: a height or a count as tagged, or none where it is below 0 or no finite number. */
  private static Double measure(Double value) {
    return value != null && Double.isFinite(value) && value >= 0 ? value : null;
  }

  @Override
  public void process(Tables.OsmBuildingPolygon element, FeatureCollector features) {
    // Imaginarium: a height or a count of storeys below 0, or no finite number, is none: -3000000000 m or -1e9 storeys
    // made a render height and sort key of Integer.MIN_VALUE, out of the sort key's range.
    Double height = measure(Parse.meters(coalesce(
      nullIfEmpty(element.height()),
      nullIfEmpty(element.buildingheight())
    )));
    Double minHeight = Parse.meters(coalesce(
      nullIfEmpty(element.minHeight()),
      nullIfEmpty(element.buildingminHeight())
    ));
    Double levels = measure(coalesce(
      parseDoubleOrNull(element.levels()),
      parseDoubleOrNull(element.buildinglevels())
    ));
    Double minLevels = coalesce(
      parseDoubleOrNull(element.minLevel()),
      parseDoubleOrNull(element.buildingminLevel())
    );

    int renderHeight = (int) Math.ceil(height != null ? height : levels != null ? (levels * 3.66) : 5);
    int renderMinHeight = (int) Math.floor(minHeight != null ? minHeight : minLevels != null ? (minLevels * 3.66) : 0);

    if (renderHeight < 3660 && renderMinHeight < 3660) {
      var feature = features.polygon(LAYER_NAME).setBufferPixels(BUFFER_SIZE)
        .setMinZoom(13)
        .setMinPixelSize(2)
        .setAttrWithMinzoom(Fields.RENDER_HEIGHT, renderHeight, 14)
        // Imaginarium: none on the ground, where a reader takes none for 0.
        .setAttrWithMinzoom(Fields.RENDER_MIN_HEIGHT, renderMinHeight == 0 ? null : renderMinHeight, 14)
        .setSortKey(renderHeight);
      // Imaginarium: each building whole, by its id and as tagged (its courtyards kept: nothing merges it away), and in
      // its home tile uncut, its parts with it (see ImaginariumBuildings).
      Imaginarium.building(feature, element.source());
      if (!ImaginariumBuildings.whole(feature, element.source(), element.buildingpart() != null)) {
        stats.dataError("imaginarium_building_cut");
      }
      if (mergeZ13Buildings) {
        feature
          .setMinPixelSize(0.1)
          .setPixelTolerance(0.25);
      }
    }
  }

  @Override
  public List<VectorTile.Feature> postProcess(int zoom,
    List<VectorTile.Feature> items) throws GeometryException {
    // Imaginarium: never merged at z14, each building its own feature with its own id; merging (which reduces the
    // size of some heavy z14 tiles with many small buildings by 60% or more) lost which building was which.
    return (mergeZ13Buildings && zoom == 13) ?
      FeatureMerge.mergeNearbyPolygons(items, 4, 4, 0.5, 0.5) :
      items;
  }

  /**
   * Imaginarium: a bridge mapped as a building (`man_made=bridge` with a `building` or a `building:part`), which
   * OpenMapTiles leaves out of its buildings and the app builds as a structure: what its building table takes, but for
   * that.
   */
  private static final Expression BRIDGE_BUILDING = Expression.and(Expression.matchAny("man_made", "bridge"),
    Tables.OsmBuildingPolygon.MAPPING.replace(Expression.not(Expression.matchAny("man_made", "bridge")),
      Expression.TRUE))
    .simplify();

  /**
   * Imaginarium: an entrance (a node tagged entrance=*), a point in the building layer with its id and tags; and a
   * bridge mapped as a building (see BRIDGE_BUILDING), as any building.
   */
  @Override
  public void processAllOsm(SourceFeature feature, FeatureCollector features) {
    if (feature.isPoint() && feature.hasTag("entrance")) {
      var entrance = features.point(LAYER_NAME).setBufferPixels(BUFFER_SIZE).setMinZoom(Imaginarium.NEAR_ZOOM);
      Imaginarium.tagged(entrance, feature, Imaginarium.ENTRANCE_TAGS);
    } else if (feature.canBePolygon() && feature.hasTag("man_made", "bridge") && BRIDGE_BUILDING.evaluate(feature)) {
      process(new Tables.OsmBuildingPolygon(feature, null), features);
    }
  }
}
