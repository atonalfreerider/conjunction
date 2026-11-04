package com.metavirtuoso.conjunction.core.astro

import kotlin.math.PI
import kotlin.math.abs

/** Small angle helpers shared by every engine. All public angles in this library are degrees. */
object Angles {
    const val DEG = PI / 180.0
    const val RAD = 180.0 / PI
    const val ARCSEC = 1.0 / 3600.0

    /** Normalises to [0, 360). */
    fun norm360(degrees: Double): Double {
        val r = degrees % 360.0
        return if (r < 0.0) r + 360.0 else if (r >= 360.0) r - 360.0 else r
    }

    /** Normalises to [-180, 180). */
    fun norm180(degrees: Double): Double = norm360(degrees + 180.0) - 180.0

    /** Smallest separation between two longitudes, in [0, 180]. */
    fun separation(a: Double, b: Double): Double = abs(norm180(a - b))

    fun normRadians(radians: Double): Double {
        val twoPi = 2.0 * PI
        val r = radians % twoPi
        return if (r < 0.0) r + twoPi else r
    }
}
