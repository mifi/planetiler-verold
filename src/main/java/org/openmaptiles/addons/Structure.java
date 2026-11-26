package org.openmaptiles.addons;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.reader.SourceFeature;
import org.openmaptiles.Layer;
import org.openmaptiles.OpenMapTilesProfile;
import org.openmaptiles.util.Imaginarium;

/**
 * Imaginarium: the `structure` layer, what stands along a line, man-made or natural (as Mapbox Streets' layer of that
 * name holds fences, walls, hedges and cliffs): barriers, power lines, rows of trees and cliffs, each by its key as
 * `class` and its value as `subclass` (see {@link Imaginarium#LINED}), at the near zoom only. A power line keeps every
 * node it has, which are its towers; the rest are simplified as any line.
 */
public class Structure implements Layer, OpenMapTilesProfile.OsmAllProcessor {

  public static final String LAYER_NAME = "structure";

  @Override
  public String name() {
    return LAYER_NAME;
  }

  @Override
  public void processAllOsm(SourceFeature feature, FeatureCollector features) {
    var lined = feature.canBeLine() ? Imaginarium.standing(Imaginarium.LINED, feature) : null;
    if (lined == null) {
      return;
    }
    var line = features.line(LAYER_NAME);
    Imaginarium.stands(lined, feature, line);
    if ("power".equals(lined.key())) {
      line.setPixelTolerance(0).setPixelToleranceAtMaxZoom(0);
    }
  }
}
