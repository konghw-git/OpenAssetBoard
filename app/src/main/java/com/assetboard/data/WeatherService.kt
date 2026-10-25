package com.assetboard.data

import com.assetboard.domain.AppClock
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.coroutines.resume

data class Place(val lat: Double, val lon: Double, val city: String?)

@Serializable
private data class DailyBlock(
    val time: List<String> = emptyList(),
    val weather_code: List<Int?> = emptyList(),
    val temperature_2m_max: List<Double?> = emptyList(),
    val temperature_2m_min: List<Double?> = emptyList(),
)

@Serializable
private data class ForecastResponse(val daily: DailyBlock? = null)

@Serializable
private data class GeoHit(val name: String, val latitude: Double, val longitude: Double, val admin1: String? = null, val country: String? = null)

@Serializable
private data class GeoResponse(val results: List<GeoHit> = emptyList())

/**
 * Weather from Open-Meteo: the forecast endpoint (with `past_days`) for the last 92 days, the
 * archive endpoint for older dates. Location from the network provider (no Google services on
 * vivo phones). Only rough coordinates leave the phone.
 */
class WeatherService(private val context: Context, private val settings: SettingsStore) {
    private val json = Json { ignoreUnknownKeys = true }

    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** A fresh network fix, else the last successful one if under 6 hours old. */
    suspend fun locate(): Place? = withContext(Dispatchers.IO) {
        val fix = if (hasLocationPermission()) runCatching { currentLocation() }.getOrNull() else null
        if (fix != null) {
            val city = cityName(fix.latitude, fix.longitude)
            settings.putString(KEY_LAST_PLACE, "${fix.latitude},${fix.longitude},${city.orEmpty()}")
            settings.putLong(KEY_LAST_PLACE_AT, AppClock.millis())
            return@withContext Place(fix.latitude, fix.longitude, city)
        }
        val at = settings.long(KEY_LAST_PLACE_AT)
        if (AppClock.millis() - at > 6 * 3600_000L) return@withContext null
        settings.string(KEY_LAST_PLACE)?.split(",", limit = 3)?.let { p ->
            Place(p[0].toDouble(), p[1].toDouble(), p.getOrNull(2)?.ifEmpty { null })
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        }
        val recent = lm.getLastKnownLocation(provider)?.takeIf { AppClock.millis() - it.time < 30 * 60_000 }
        if (recent != null) return recent
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return lm.getLastKnownLocation(provider)
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { cont.resume(it) }
            }
        } ?: lm.getLastKnownLocation(provider)
    }

    @Suppress("DEPRECATION")
    private fun cityName(lat: Double, lon: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        val a = Geocoder(context, Locale.CHINA).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return null
        a.locality ?: a.subAdminArea ?: a.adminArea
    }.getOrNull()

    /** Place search for choosing a city by hand. */
    suspend fun searchCity(name: String): List<Place> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val text = get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=zh&format=json")
        json.decodeFromString(GeoResponse.serializer(), text).results.map {
            Place(it.latitude, it.longitude, listOfNotNull(it.name, it.admin1?.takeIf { a -> a != it.name }).joinToString(" · "))
        }
    }

    /** Throws on network failure; null when the service has no data for that day. */
    suspend fun daily(place: Place, date: LocalDate, today: LocalDate = AppClock.today()): WeatherResult? = withContext(Dispatchers.IO) {
        val ago = ChronoUnit.DAYS.between(date, today)
        val daily = "daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto"
        val url = if (ago in 0..92) {
            "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}&$daily&past_days=${ago.coerceAtLeast(0)}&forecast_days=1"
        } else {
            "https://archive-api.open-meteo.com/v1/archive?latitude=${place.lat}&longitude=${place.lon}&$daily&start_date=$date&end_date=$date"
        }
        val block = json.decodeFromString(ForecastResponse.serializer(), get(url)).daily ?: return@withContext null
        val i = block.time.indexOf(date.toString()).takeIf { it >= 0 } ?: return@withContext null
        val code = block.weather_code.getOrNull(i) ?: return@withContext null
        WeatherResult(code, block.temperature_2m_min.getOrNull(i), block.temperature_2m_max.getOrNull(i), place.city, place.lat, place.lon)
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            return c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val KEY_LAST_PLACE = "last_place"
        private const val KEY_LAST_PLACE_AT = "last_place_at"

        /** WMO weather code → short Chinese description. */
        fun describe(code: Int?): String = when (code) {
            null -> ""
            0 -> "晴"
            1 -> "晴间多云"
            2 -> "多云"
            3 -> "阴"
            45, 48 -> "雾"
            51, 53, 55, 56, 57 -> "毛毛雨"
            61, 80 -> "小雨"
            63, 81 -> "中雨"
            65, 82 -> "大雨"
            66, 67 -> "冻雨"
            71, 85 -> "小雪"
            73 -> "中雪"
            75, 86 -> "大雪"
            77 -> "雪粒"
            95 -> "雷阵雨"
            96, 99 -> "雷阵雨伴冰雹"
            else -> "天气 $code"
        }
    }
}
