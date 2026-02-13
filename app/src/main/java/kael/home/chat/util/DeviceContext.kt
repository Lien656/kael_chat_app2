package kael.home.chat.util

import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import java.util.Locale

/**
 * Контекст устройства для Каэля: геолокация (где Лиэн), батарея, сеть, время уже в промпте.
 * Собирается раз за запрос и передаётся в system prompt.
 */
object DeviceContext {

    fun get(context: Context): String {
        val parts = mutableListOf<String>()
        // Геолокация (если есть разрешение и последнее место известно)
        val locationStr = getLocation(context)
        if (locationStr != null) parts.add("Место: $locationStr")
        // Батарея
        parts.add("Батарея: ${getBattery(context)}")
        // Сеть
        parts.add("Сеть: ${getNetwork(context)}")
        if (parts.isEmpty()) return ""
        return parts.joinToString(". ")
    }

    private fun getLocation(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return null
        val locMan = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val location = locMan.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: locMan.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: return null
        val lat = location.latitude
        val lon = location.longitude
        val city = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { a ->
                    listOfNotNull(a.locality, a.countryName).filter { it.isNotEmpty() }.joinToString(", ")
                }
            } else {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { a ->
                    listOfNotNull(a.locality, a.countryName).filter { it.isNotEmpty() }.joinToString(", ")
                }
            }
        } catch (_: Exception) { null }
        return when {
            !city.isNullOrBlank() -> city
            else -> "%.4f, %.4f".format(locale = Locale.US, lat, lon)
        }
    }

    private fun getBattery(context: Context): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return "неизвестно"
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return "$level%" + if (charging) ", заряжается" else ""
    }

    private fun getNetwork(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "неизвестно"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "есть"
        val net = cm.activeNetwork ?: return "нет"
        val caps = cm.getNetworkCapabilities(net) ?: return "есть"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная"
            else -> "есть"
        }
    }
}
