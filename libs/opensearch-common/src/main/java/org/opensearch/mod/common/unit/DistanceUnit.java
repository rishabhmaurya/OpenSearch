/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.mod.common.unit;

import org.opensearch.mod.common.io.stream.StreamInput;
import org.opensearch.mod.common.io.stream.StreamOutput;
import org.opensearch.mod.common.io.stream.Writeable;

import java.io.IOException;

/**
 * The DistanceUnit enumerates several units for measuring distances. These units
 * provide methods for converting strings and methods to convert units among each
 * others. Some methods like {@link DistanceUnit#getEarthCircumference} refer to
 * the earth ellipsoid defined in {@link GeoUtils}. The default unit used within
 * this project is <code>METERS</code> which is defined by <code>DEFAULT</code>
 */
public enum DistanceUnit implements Writeable {
    INCH(0.0254, "in", "inch"),
    YARD(0.9144, "yd", "yards"),
    FEET(0.3048, "ft", "feet"),
    KILOMETERS(1000.0, "km", "kilometers"),
    NAUTICALMILES(1852.0, "NM", "nmi", "nauticalmiles"),
    MILLIMETERS(0.001, "mm", "millimeters"),
    CENTIMETERS(0.01, "cm", "centimeters"),

    // 'm' is a suffix of 'nmi' so it must follow 'nmi'
    MILES(1609.344, "mi", "miles"),

    // since 'm' is suffix of other unit
    // it must be the last entry of unit
    // names ending with 'm'. otherwise
    // parsing would fail
    METERS(1, "m", "meters");

    public static final DistanceUnit DEFAULT = METERS;

    private double meters;
    private final String[] names;

    DistanceUnit(double meters, String...names) {
        this.meters = meters;
        this.names = names;
    }

    // #RF refactored - moved these constants from GeoUtils to here
    static class GeoUtils {
        /** Maximum valid latitude in degrees. */
        public static final double MAX_LAT = 90.0;
        /** Minimum valid latitude in degrees. */
        public static final double MIN_LAT = -90.0;
        /** Maximum valid longitude in degrees. */
        public static final double MAX_LON = 180.0;
        /** Minimum valid longitude in degrees. */
        public static final double MIN_LON = -180.0;

        public static final String LATITUDE = "lat";
        public static final String LONGITUDE = "lon";
        public static final String GEOHASH = "geohash";

        /** Earth ellipsoid major axis defined by WGS 84 in meters */
        public static final double EARTH_SEMI_MAJOR_AXIS = 6378137.0;      // meters (WGS 84)

        /** Earth ellipsoid minor axis defined by WGS 84 in meters */
        public static final double EARTH_SEMI_MINOR_AXIS = 6356752.314245; // meters (WGS 84)

        /** Earth mean radius defined by WGS 84 in meters */
        public static final double EARTH_MEAN_RADIUS = 6371008.7714D;      // meters (WGS 84)

        /** Earth axis ratio defined by WGS 84 (0.996647189335) */
        public static final double EARTH_AXIS_RATIO = EARTH_SEMI_MINOR_AXIS / EARTH_SEMI_MAJOR_AXIS;

        /** Earth ellipsoid equator length in meters */
        public static final double EARTH_EQUATOR = 2*Math.PI * EARTH_SEMI_MAJOR_AXIS;

        /** Earth ellipsoid polar distance in meters */
        public static final double EARTH_POLAR_DISTANCE = Math.PI * EARTH_SEMI_MINOR_AXIS;

        /** rounding error for quantized latitude and longitude values */
        public static final double TOLERANCE = 1E-6;
    }
    /**
     * Measures the circumference of earth in this unit
     *
     * @return length of earth circumference in this unit
     */
    public double getEarthCircumference() {
        return GeoUtils.EARTH_EQUATOR / meters;
    }

    /**
     * Measures the radius of earth in this unit
     *
     * @return length of earth radius in this unit
     */
    public double getEarthRadius() {
        return GeoUtils.EARTH_SEMI_MAJOR_AXIS / meters;
    }

    /**
     * Measures a longitude in this unit
     *
     * @return length of a longitude degree in this unit
     */
    public double getDistancePerDegree() {
        return GeoUtils.EARTH_EQUATOR / (360.0 * meters);
    }

    /**
     * Convert a value into meters
     *
     * @param distance distance in this unit
     * @return value in meters
     */
    public double toMeters(double distance) {
        return convert(distance, this, DistanceUnit.METERS);
    }

    /**
     * Convert a value given in meters to a value of this unit
     *
     * @param distance distance in meters
     * @return value in this unit
     */
    public double fromMeters(double distance) {
        return convert(distance, DistanceUnit.METERS, this);
    }

    /**
     * Convert a given value into another unit
     *
     * @param distance value in this unit
     * @param unit source unit
     * @return value in this unit
     */
    public double convert(double distance, DistanceUnit unit) {
        return convert(distance, unit, this);
    }

    /**
     * Convert a value to a distance string
     *
     * @param distance value to convert
     * @return String representation of the distance
     */
    public String toString(double distance) {
        return distance + toString();
    }

    @Override
    public String toString() {
        return names[0];
    }

    /**
     * Converts the given distance from the given DistanceUnit, to the given DistanceUnit
     *
     * @param distance Distance to convert
     * @param from     Unit to convert the distance from
     * @param to       Unit of distance to convert to
     * @return Given distance converted to the distance in the given unit
     */
    public static double convert(double distance, DistanceUnit from, DistanceUnit to) {
        if (from == to) {
            return distance;
        } else {
            return distance * from.meters / to.meters;
        }
    }

    /**
     * Parses a given distance and converts it to the specified unit.
     *
     * @param distance String defining a distance (value and unit)
     * @param defaultUnit unit assumed if none is defined
     * @param to unit of result
     * @return parsed distance
     */
    public static double parse(String distance, DistanceUnit defaultUnit, DistanceUnit to) {
        Distance dist = Distance.parseDistance(distance, defaultUnit);
        return convert(dist.value, dist.unit, to);
    }

    /**
     * Parses a given distance and converts it to this unit.
     *
     * @param distance String defining a distance (value and unit)
     * @param defaultUnit unit to expect if none if provided
     * @return parsed distance
     */
    public double parse(String distance, DistanceUnit defaultUnit) {
        return parse(distance, defaultUnit, this);
    }

    /**
     * Convert a String to a {@link DistanceUnit}
     *
     * @param unit name of the unit
     * @return unit matching the given name
     * @throws IllegalArgumentException if no unit matches the given name
     */
    public static DistanceUnit fromString(String unit) {
        for (DistanceUnit dunit : values()) {
            for (String name : dunit.names) {
                if(name.equals(unit)) {
                    return dunit;
                }
            }
        }
        throw new IllegalArgumentException("No distance unit match [" + unit + "]");
    }

    /**
     * Parses the suffix of a given distance string and return the corresponding {@link DistanceUnit}
     *
     * @param distance string representing a distance
     * @param defaultUnit default unit to use, if no unit is provided by the string
     * @return unit of the given distance
     */
    public static DistanceUnit parseUnit(String distance, DistanceUnit defaultUnit) {
        for (DistanceUnit unit : values()) {
            for (String name : unit.names) {
                if(distance.endsWith(name)) {
                    return unit;
                }
            }
        }
        return defaultUnit;
    }

    /**
     * This class implements a value+unit tuple.
     */
    public static class Distance implements Comparable<Distance> {
        public final double value;
        public final DistanceUnit unit;

        public Distance(double value, DistanceUnit unit) {
            super();
            this.value = value;
            this.unit = unit;
        }

        /**
         * Converts a {@link Distance} value given in a specific {@link DistanceUnit} into
         * a value equal to the specified value but in a other {@link DistanceUnit}.
         *
         * @param unit unit of the result
         * @return converted distance
         */
        public Distance convert(DistanceUnit unit) {
            if(this.unit == unit) {
                return this;
            } else {
                return new Distance(DistanceUnit.convert(value, this.unit, unit), unit);
            }
        }

        @Override
        public boolean equals(Object obj) {
            if(obj == null) {
                return false;
            } else if (obj instanceof Distance) {
                Distance other = (Distance) obj;
                return DistanceUnit.convert(value, unit, other.unit) == other.value;
            } else {
                return false;
            }
        }

        @Override
        public int hashCode() {
            return Double.valueOf(value * unit.meters).hashCode();
        }

        @Override
        public int compareTo(Distance o) {
            return Double.compare(value, DistanceUnit.convert(o.value, o.unit, unit));
        }

        @Override
        public String toString() {
            return unit.toString(value);
        }

        /**
         * Parse a {@link Distance} from a given String. If no unit is given
         * <code>DistanceUnit.DEFAULT</code> will be used
         *
         * @param distance String defining a {@link Distance}
         * @return parsed {@link Distance}
         */
        public static Distance parseDistance(String distance) {
            return parseDistance(distance, DEFAULT);
        }

        /**
         * Parse a {@link Distance} from a given String
         *
         * @param distance String defining a {@link Distance}
         * @param defaultUnit {@link DistanceUnit} to be assumed
         *          if not unit is provided in the first argument
         * @return parsed {@link Distance}
         */
        private static Distance parseDistance(String distance, DistanceUnit defaultUnit) {
            for (DistanceUnit unit : values()) {
                for (String name : unit.names) {
                    if(distance.endsWith(name)) {
                        return new Distance(Double.parseDouble(distance.substring(0, distance.length() - name.length())), unit);
                    }
                }
            }
            return new Distance(Double.parseDouble(distance), defaultUnit);
        }
    }

    /**
     * Read a {@link DistanceUnit} from a {@link StreamInput}.
     *
     * @param in {@link StreamInput} to read the {@link DistanceUnit} from
     * @return {@link DistanceUnit} read from the {@link StreamInput}
     * @throws IOException if no unit can be read from the {@link StreamInput}
     * @throws IllegalArgumentException if no matching {@link DistanceUnit} can be found
     */
    public static DistanceUnit readFromStream(StreamInput in) throws IOException {
        byte b = in.readByte();

        if (b < 0 || b >= values().length) {
            throw new IllegalArgumentException("No type for distance unit matching [" + b + "]");
        }
        return values()[b];
    }

    /**
     * Write a {@link DistanceUnit} to a {@link StreamOutput}.
     *
     * @param out {@link StreamOutput} to write to
     */
    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeByte((byte) this.ordinal());
    }
}
