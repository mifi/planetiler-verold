/*
Copyright (c) 2021, MapTiler.com & OpenMapTiles contributors.
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

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.stats.Stats;
import com.onthegomap.planetiler.util.Translations;
import java.util.List;
import org.openmaptiles.OpenMapTilesProfile;
import org.openmaptiles.generated.OpenMapTilesSchema;
import org.openmaptiles.generated.Tables;
import org.openmaptiles.util.Imaginarium;
import org.openmaptiles.util.Utils;

/**
 * Defines the logic for generating map elements in the {@code aeroway} layer from source features.
 * <p>
 * This class is ported to Java from
 * <a href="https://github.com/openmaptiles/openmaptiles/tree/master/layers/aeroway">OpenMapTiles aeroway sql files</a>.
 */
public class Aeroway implements
  OpenMapTilesSchema.Aeroway,
  Tables.OsmAerowayLinestring.Handler,
  Tables.OsmAerowayPolygon.Handler,
  Tables.OsmAerowayPoint.Handler,
  OpenMapTilesProfile.OsmAllProcessor {

  public Aeroway(Translations translations, PlanetilerConfig config, Stats stats) {}

  @Override
  public void process(Tables.OsmAerowayPolygon element, FeatureCollector features) {
    var feature = features.polygon(LAYER_NAME)
      .setMinZoom(10)
      .setMinPixelSize(2)
      .setAttr(Fields.CLASS, element.aeroway())
      .setAttr(Fields.REF, element.ref());
    laid(feature, element.source());
  }

  @Override
  public void process(Tables.OsmAerowayLinestring element, FeatureCollector features) {
    var feature = features.line(LAYER_NAME)
      .setMinZoom(10)
      .setAttr(Fields.CLASS, element.aeroway())
      .setAttr(Fields.REF, element.ref());
    laid(feature, element.source());
  }

  /**
   * Imaginarium: what a runway or a taxiway carries beside its class and ref, an area of an airfield too, at the near
   * zoom, as the transportation layer's ways carry it: `brunnel` (a bridge or a tunnel) and `layer`, `tunnel` and its
   * `width` as tagged (a passage through a building a `tunnel=building_passage`), and its `surface` as OpenMapTiles
   * reduces a road's (paved or unpaved), the tagged value beside it as `surface:osm`.
   */
  private static void laid(FeatureCollector.Feature feature, SourceFeature source) {
    feature
      .setAttrWithMinzoom(Transportation.Fields.BRUNNEL,
        Utils.brunnel(source.getBoolean("bridge"), source.getBoolean("tunnel")), Imaginarium.NEAR_ZOOM)
      .setAttrWithMinzoom(Transportation.Fields.LAYER, Utils.nullIfLong(source.getLong("layer"), 0),
        Imaginarium.NEAR_ZOOM)
      .setAttrWithMinzoom(Transportation.Fields.SURFACE, Transportation.surface(source.getString("surface")),
        Imaginarium.NEAR_ZOOM)
      .setAttrWithMinzoom("width", Utils.nullIfEmpty(source.getString("width")), Imaginarium.NEAR_ZOOM)
      .setAttrWithMinzoom("tunnel", Utils.nullIfEmpty(source.getString("tunnel")), Imaginarium.NEAR_ZOOM);
    Imaginarium.taggedRenamed(feature, source, List.of("surface"));
  }

  @Override
  public void process(Tables.OsmAerowayPoint element, FeatureCollector features) {
    features.point(LAYER_NAME)
      .setMinZoom(14)
      .setAttr(Fields.CLASS, element.aeroway())
      .setAttr(Fields.REF, element.ref());
  }

  /**
   * Imaginarium: a helipad mapped as a point (OpenMapTiles has only those mapped as areas), from the far zoom on, as
   * the helipad areas are read.
   */
  @Override
  public void processAllOsm(SourceFeature feature, FeatureCollector features) {
    if (feature.isPoint() && feature.hasTag("aeroway", "helipad")) {
      features.point(LAYER_NAME)
        .setMinZoom(Imaginarium.FAR_ZOOM)
        .setAttr(Fields.CLASS, "helipad")
        .setAttr(Fields.REF, feature.getString("ref"));
    }
  }
}
