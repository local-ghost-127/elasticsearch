/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.search.aggregations.bucket.geogrid;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedNumericDocValues;
import org.elasticsearch.common.geo.GeoBoundingBox;
import org.elasticsearch.geometry.utils.Geohash;
import org.elasticsearch.index.fielddata.MultiGeoPointValues;
import org.elasticsearch.index.fielddata.SortedBinaryDocValues;
import org.elasticsearch.index.fielddata.SortedNumericDoubleValues;
import org.elasticsearch.search.aggregations.support.ValuesSource;

/**
 * Class to help convert {@link MultiGeoPointValues}
 * to GeoHash bucketing.
 */
public class GeoHashCellIdSource extends ValuesSource.Numeric {
    private final GeoPoint valuesSource;
    private final int precision;
    private final GeoBoundingBox geoBoundingBox;

    public GeoHashCellIdSource(GeoPoint valuesSource, int precision, GeoBoundingBox geoBoundingBox) {
        this.valuesSource = valuesSource;
        this.precision = precision;
        this.geoBoundingBox = geoBoundingBox;
    }

    public int precision() {
        return precision;
    }

    @Override
    public boolean isFloatingPoint() {
        return false;
    }

    @Override
    public SortedNumericDocValues longValues(LeafReaderContext ctx) {
        return geoBoundingBox.isUnbounded()
            ? new UnboundedCellValues(valuesSource.geoPointValues(ctx), precision)
            : new BoundedCellValues(valuesSource.geoPointValues(ctx), precision, geoBoundingBox);
    }

    @Override
    public SortedNumericDoubleValues doubleValues(LeafReaderContext ctx) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SortedBinaryDocValues bytesValues(LeafReaderContext ctx) {
        throw new UnsupportedOperationException();
    }

    private static class UnboundedCellValues extends CellValues {

        UnboundedCellValues(MultiGeoPointValues geoValues, int precision) {
            super(geoValues, precision);
        }

        @Override
        protected int advanceValue(org.elasticsearch.common.geo.GeoPoint target, int valuesIdx) {
            values[valuesIdx] = Geohash.longEncode(target.getLon(), target.getLat(), precision);
            return valuesIdx + 1;
        }
    }

    private static class BoundedCellValues extends CellValues {

        private final GeoHashBoundedPredicate predicate;
        private final GeoBoundingBox bbox;

        BoundedCellValues(MultiGeoPointValues geoValues, int precision, GeoBoundingBox bbox) {
            super(geoValues, precision);
            this.predicate = new GeoHashBoundedPredicate(precision, bbox);
            this.bbox = bbox;
        }

        @Override
        protected int advanceValue(org.elasticsearch.common.geo.GeoPoint target, int valuesIdx) {
            final String hash = Geohash.stringEncode(target.getLon(), target.getLat(), precision);
            if (validPoint(target.getLon(), target.getLat()) || predicate.validHash(hash)) {
                values[valuesIdx] = Geohash.longEncode(hash);
                return valuesIdx + 1;
            }
            return valuesIdx;
        }

        private boolean validPoint(double x, double y) {
            if (bbox.top() > y && bbox.bottom() < y) {
                boolean crossesDateline = bbox.left() > bbox.right();
                if (crossesDateline) {
                    return bbox.left() < x || bbox.right() > x;
                } else {
                    return bbox.left() < x && bbox.right() > x;
                }
            }
            return false;
        }
    }
}
