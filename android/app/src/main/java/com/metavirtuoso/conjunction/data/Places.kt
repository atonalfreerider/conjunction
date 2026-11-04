package com.metavirtuoso.conjunction.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.metavirtuoso.conjunction.tz.TimeZoneGrid
import com.metavirtuoso.conjunction.tz.ZoneTable
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** A birth place candidate with the time zone resolved offline from its coordinates. */
data class PlaceResult(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val zoneId: String,
    /** e.g. "UTC−05:00 (CDT) on that date" for the chosen birth date/time. */
    val offsetLabel: String,
)

class PlaceSearchException(message: String) : Exception(message)

/**
 * Birth-place search: android.location.Geocoder for the coordinates (the platform geocoder, which
 * needs a network connection on most phones), then [TimeZoneGrid] — fully offline — for the IANA
 * zone. The offset shown is the one in force at the birth date and time (historical DST).
 */
object Places {
    fun geocoderAvailable(): Boolean = Geocoder.isPresent()

    suspend fun search(context: Context, query: String, grid: TimeZoneGrid?, table: ZoneTable, date: LocalDate, time: LocalTime?): List<PlaceResult> {
        if (query.isBlank()) return emptyList()
        if (!Geocoder.isPresent()) throw PlaceSearchException("This phone has no place search. Enter coordinates instead.")
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses: List<Address> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocationName(query, MAX_RESULTS, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses) }
                        override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(emptyList()) }
                    })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(query, MAX_RESULTS).orEmpty()
                }
            }
        } catch (e: IOException) {
            throw PlaceSearchException("Place search needs a connection. Try again, or enter coordinates.")
        }
        return withContext(Dispatchers.Default) {
            addresses.filter { it.hasLatitude() && it.hasLongitude() }
                .map { a -> resolve(label(a), a.latitude, a.longitude, grid, table, date, time, a.countryCode) }
                .distinctBy { it.name }
        }
    }

    /**
     * The zone for a place: the offline [grid] when it is available and agrees with the geocoded
     * country, otherwise the nearest tz-database reference city in that country ([table]).
     */
    fun resolve(
        name: String, lat: Double, lon: Double, grid: TimeZoneGrid?, table: ZoneTable,
        date: LocalDate, time: LocalTime?, countryCode: String? = null,
    ): PlaceResult {
        val fromGrid = grid?.let { runCatching { it.zoneFor(lat, lon) }.getOrNull() }
            ?.takeIf { countryCode == null || table.zoneBelongsTo(it.id, countryCode) }
        val zone = fromGrid ?: table.zoneFor(lat, lon, countryCode)
        return PlaceResult(name, lat, lon, zone.id, offsetLabel(zone, date, time))
    }

    fun offsetLabel(zone: ZoneId, date: LocalDate, time: LocalTime?): String {
        val zdt = ZonedDateTime.of(date, time ?: LocalTime.NOON, zone)
        val offset = zdt.offset.id.let { if (it == "Z") "+00:00" else it }.replace("-", "−")
        val abbreviation = java.time.format.DateTimeFormatter.ofPattern("zzz", Locale.US).format(zdt)
        return "UTC$offset ($abbreviation) on that date"
    }

    fun label(a: Address): String = listOfNotNull(
        a.locality ?: a.subAdminArea ?: a.featureName,
        a.adminArea?.takeIf { it != a.locality },
        a.countryName,
    ).distinct().joinToString(", ").ifEmpty { "%.4f, %.4f".format(Locale.ROOT, a.latitude, a.longitude) }

    private const val MAX_RESULTS = 6
}
