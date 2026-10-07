package com.dermalens.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.dermalens.app.BuildConfig
import com.dermalens.app.ui.LocalAppSettings
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class Clinic(
    val name: String,
    val address: String,
    val distance: String,
    /** null when Google didn't say -- shown as "Hours unknown", never guessed as open. */
    val openNow: Boolean?,
    val hours: String,
    val phone: String,
    val lat: Double,
    val lng: Double,
    // Google's Place ID -- lets "Report incorrect info" open this *exact* branch's real Maps
    // page rather than a name-based search, which matters here since chains like "Professional
    // Skin Care Formula By Dr. Alvin" have dozens of identically-named branches nationwide.
    val placeId: String
)

/** Pulls just today's line out of [hours] (the full week, one line per day, Monday first --
 *  matching Google Places' regularOpeningHours.weekdayDescriptions order) so a compact card can
 *  show "opens/closes at X" without the user needing to tap in for the full 7-line schedule.
 *  Falls back to the raw string as-is for the "Contact clinic for hours" case, or anything else
 *  that isn't a real 7-line week. */
private fun todaysHoursLine(hours: String): String {
    val lines = hours.split("\n").filter { it.isNotBlank() }
    if (lines.size != 7) return hours
    // Calendar.DAY_OF_WEEK is Sunday=1..Saturday=7; convert to Monday-first index 0-6.
    val calendarDay = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
    val mondayFirstIndex = (calendarDay + 5) % 7
    val line = lines.getOrElse(mondayFirstIndex) { return hours }
    // Google's line already reads "Monday: 9:00 AM - 5:00 PM" -- drop the redundant day name
    // for a compact card that's already implicitly about "today".
    return line.substringAfter(": ", line)
}

private fun createUserDotBitmap(context: Context): Bitmap {
    val dp = context.resources.displayMetrics.density
    val size = (22 * dp).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = size / 2f
    paint.color = 0xFFFFFFFF.toInt()
    canvas.drawCircle(cx, cx, cx, paint)
    paint.color = 0xFF7C3AED.toInt()
    canvas.drawCircle(cx, cx, cx * 0.62f, paint)
    return bitmap
}

private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

/** A drawn clinic pin plus where its tip touches the map, as a fraction of the bitmap height (the marker anchor). */
private class PinBitmap(val bitmap: Bitmap, val anchorY: Float)

/**
 * Clinic pin: a small rounded-square tile in the clinic's status [color] with a white rounded "+"
 * and a short pointer whose tip marks the spot. Square rather than round so it doesn't blend in
 * with Google's own round place icons. [selected] draws it larger.
 */
private fun createMarkerBitmap(context: Context, color: Int, selected: Boolean = false): PinBitmap {
    val s = context.resources.displayMetrics.density
    val tile = (if (selected) 38f else 28f) * s
    val rim = (if (selected) 2.5f else 2f) * s
    val corner = tile * 0.32f
    val pointer = (if (selected) 7f else 5.5f) * s
    val pad = 4f * s                            // room for the shadow blur
    val w = (tile + 2 * pad).toInt()
    val left = (w - tile) / 2f
    val top = pad
    val cx = w / 2f
    val cy = top + tile / 2f
    val tipY = top + tile + pointer
    val h = (tipY + pad + 1f * s).toInt()

    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun silhouette(inset: Float) = Path().apply {
        val box = android.graphics.RectF(left + inset, top + inset, left + tile - inset, top + tile - inset)
        addRoundRect(box, corner - inset, corner - inset, Path.Direction.CW)
        op(Path().apply {
            moveTo(cx - pointer, top + tile - inset - 1f * s)
            lineTo(cx, tipY - inset * 1.6f)
            lineTo(cx + pointer, top + tile - inset - 1f * s)
            close()
        }, Path.Op.UNION)
    }

    // White silhouette carries the shadow; the coloured tile sits inside it, leaving a white rim.
    paint.style = Paint.Style.FILL
    paint.color = 0xFFFFFFFF.toInt()
    paint.setShadowLayer(3f * s, 0f, 1.2f * s, 0x40000000)
    canvas.drawPath(silhouette(0f), paint)
    paint.clearShadowLayer()

    paint.color = color
    canvas.drawPath(silhouette(rim), paint)

    // Rounded "+".
    paint.color = 0xFFFFFFFF.toInt()
    val arm = tile * 0.24f
    val thick = tile * 0.075f
    canvas.drawRoundRect(android.graphics.RectF(cx - thick, cy - arm, cx + thick, cy + arm), thick, thick, paint)
    canvas.drawRoundRect(android.graphics.RectF(cx - arm, cy - thick, cx + arm, cy + thick), thick, thick, paint)

    return PinBitmap(bitmap, tipY / h)
}

/** Pin colours: the cards' open/closed/unknown hues, a step brighter so they hold up at pin size. */
private fun pinColor(openNow: Boolean?): Int = when (openNow) {
    true -> 0xFF16A34A.toInt()
    false -> 0xFFDC2626.toInt()
    null -> 0xFF64748B.toInt()
}

private fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private suspend fun fetchNearbyClinics(context: Context, lat: Double, lng: Double): List<Clinic> {
    return withContext(Dispatchers.IO) {
        val places = runPlacesTextSearch(context, lat, lng)
        val result = mutableListOf<Clinic>()
        for (place in places) {
            // Google's own index can lag reality -- a place that's shut down for good doesn't
            // always get pruned from search results right away. Recommending a dermatology
            // clinic that no longer exists to someone trying to get a skin condition looked at
            // is a real harm, not a cosmetic issue, so permanently-closed places are dropped
            // entirely rather than just shown with a "Closed" badge (that badge already means
            // "closed right now, per today's hours" -- a different, temporary thing).
            val businessStatus = place.optString("businessStatus", "OPERATIONAL")
            if (businessStatus == "CLOSED_PERMANENTLY") continue

            val name = place.optJSONObject("displayName")?.optString("text")?.takeIf { it.isNotEmpty() } ?: continue
            val loc = place.optJSONObject("location") ?: continue
            val elLat = loc.optDouble("latitude", Double.NaN).takeIf { !it.isNaN() } ?: continue
            val elLng = loc.optDouble("longitude", Double.NaN).takeIf { !it.isNaN() } ?: continue
            val dist = haversineKm(lat, lng, elLat, elLng)
            val addr = place.optString("formattedAddress").ifEmpty { "Tarlac, Philippines" }
            val phone = place.optString("internationalPhoneNumber").ifEmpty { "N/A" }
            val openingHours = place.optJSONObject("regularOpeningHours")
            val temporarilyClosed = businessStatus == "CLOSED_TEMPORARILY"
            // A temporary closure overrides today's regular hours -- showing "Open Now" from a
            // weekly schedule Google itself says isn't currently honored would be misleading, and
            // worse than just not knowing.
            val openNow = if (temporarilyClosed) false else if (openingHours?.has("openNow") == true) openingHours.optBoolean("openNow") else null
            val hours = if (temporarilyClosed) "Temporarily closed" else openingHours?.optJSONArray("weekdayDescriptions")?.let { arr ->
                (0 until arr.length()).joinToString("\n") { arr.getString(it) }
            }?.takeIf { it.isNotEmpty() } ?: "Contact clinic for hours"
            val placeId = place.optString("id")
            result.add(Clinic(name, addr, "%.1f km".format(dist), openNow, hours, phone, elLat, elLng, placeId))
        }
        result.sortedBy { haversineKm(lat, lng, it.lat, it.lng) }
    }
}

/** Runs a Places API (New) Text Search biased to the user's location, returning its "places"
 *  entries, or an empty list on any failure/timeout. Text Search (rather than Nearby Search,
 *  which only filters by fixed place types) is what lets "dermatology clinic" match by name and
 *  category the way the previous Overpass healthcare-speciality tag search did. */
// Same SHA-1 used for the API key's Android app restriction in Google Cloud Console, but without
// colons -- that's the format the X-Android-Cert header expects.
private const val ANDROID_CERT_SHA1 = "0F0F3D55E975E6572B6FD1B4C53079D2B7E6ECEF"

private fun runPlacesTextSearch(context: Context, lat: Double, lng: Double): List<org.json.JSONObject> {
    return try {
        val body = org.json.JSONObject().apply {
            put("textQuery", "dermatology clinic")
            put("locationBias", org.json.JSONObject().apply {
                put("circle", org.json.JSONObject().apply {
                    put("center", org.json.JSONObject().apply {
                        put("latitude", lat)
                        put("longitude", lng)
                    })
                    put("radius", 15000.0)
                })
            })
        }
        val url = java.net.URL("https://places.googleapis.com/v1/places:searchText")
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Goog-Api-Key", BuildConfig.MAPS_API_KEY)
            setRequestProperty(
                "X-Goog-FieldMask",
                "places.id,places.displayName,places.formattedAddress,places.location,places.regularOpeningHours,places.internationalPhoneNumber,places.businessStatus"
            )
            // The key is restricted to this Android app (package + SHA-1 cert), but that
            // restriction is normally enforced via headers the official Places SDK adds
            // automatically -- a raw HttpURLConnection call like this one has to attach them
            // itself, or Google sees no app identity at all and blocks the request outright
            // (confirmed via logcat: "API_KEY_ANDROID_APP_BLOCKED", androidPackage: "<empty>").
            setRequestProperty("X-Android-Package", context.packageName)
            setRequestProperty("X-Android-Cert", ANDROID_CERT_SHA1)
            outputStream.use { it.write(body.toString().toByteArray()) }
        }
        if (conn.responseCode !in 200..299) {
            val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "(no error body)"
            android.util.Log.e("DermaLens", "Places search failed: HTTP ${conn.responseCode} -- $errorBody")
            return emptyList()
        }
        val response = conn.inputStream.bufferedReader().readText()
        val places = org.json.JSONObject(response).optJSONArray("places") ?: return emptyList()
        (0 until places.length()).map { places.getJSONObject(it) }
    } catch (e: Exception) {
        android.util.Log.e("DermaLens", "Places search failed", e)
        emptyList()
    }
}

/** Decodes a Google-encoded polyline (the standard format Routes API returns -- see
 *  https://developers.google.com/maps/documentation/utilities/polylinealgorithm) into raw
 *  points. Hand-rolled rather than pulling in `com.google.maps.android:android-maps-utils` for
 *  one function -- this screen already talks to Google's Places API via a raw HttpURLConnection
 *  rather than through an SDK (see [runPlacesTextSearch]'s comment), so this matches that. */
private fun decodePolyline(encoded: String): List<LatLng> {
    val points = mutableListOf<LatLng>()
    var index = 0
    var lat = 0
    var lng = 0
    while (index < encoded.length) {
        var shift = 0
        var result = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lat += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)

        shift = 0
        result = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lng += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)

        points.add(LatLng(lat / 1e5, lng / 1e5))
    }
    return points
}

/** A resolved driving route: the polyline to draw, plus the real driving distance/duration --
 *  as opposed to [Clinic.distance], which is straight-line haversine and can meaningfully
 *  understate actual travel (a river, a highway with no nearby crossing, etc.). */
data class RouteInfo(val points: List<LatLng>, val distanceMeters: Int, val durationSeconds: Int)

/** Real driving route from Google's Routes API (`computeRoutes`) -- the same generation of API
 *  as [runPlacesTextSearch] (not the older Directions API, not the Maps SDK), reusing the same
 *  Android-app-restricted key and the same manually-attached X-Android-Package/X-Android-Cert
 *  headers that restriction requires for a raw HTTP call. Replaced the free OSRM public demo
 *  server now that clinic search already depends on Google/billing anyway -- see the caller's
 *  distance-based debounce for why this isn't fired on every single location tick. Returns null
 *  on failure (straight-line haversine is still shown via [Clinic.distance] either way, so there's
 *  no need for this to also carry its own straight-line fallback the way the polyline-only
 *  version used to). */
private suspend fun fetchRoute(context: Context, fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): RouteInfo? {
    return withContext(Dispatchers.IO) {
        try {
            val body = org.json.JSONObject().apply {
                put("origin", org.json.JSONObject().apply {
                    put("location", org.json.JSONObject().apply {
                        put("latLng", org.json.JSONObject().apply {
                            put("latitude", fromLat)
                            put("longitude", fromLng)
                        })
                    })
                })
                put("destination", org.json.JSONObject().apply {
                    put("location", org.json.JSONObject().apply {
                        put("latLng", org.json.JSONObject().apply {
                            put("latitude", toLat)
                            put("longitude", toLng)
                        })
                    })
                })
                put("travelMode", "DRIVE")
            }
            val url = java.net.URL("https://routes.googleapis.com/directions/v2:computeRoutes")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Goog-Api-Key", BuildConfig.MAPS_API_KEY)
                // Routes API requires an explicit field mask on every request (unlike the old
                // Directions API) -- duration/distanceMeters ride along on the same request as
                // the polyline, so showing real driving time/distance instead of straight-line
                // costs nothing extra over what was already being called.
                setRequestProperty("X-Goog-FieldMask", "routes.polyline.encodedPolyline,routes.duration,routes.distanceMeters")
                setRequestProperty("X-Android-Package", context.packageName)
                setRequestProperty("X-Android-Cert", ANDROID_CERT_SHA1)
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if (conn.responseCode !in 200..299) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "(no error body)"
                android.util.Log.e("DermaLens", "Routes API failed: HTTP ${conn.responseCode} -- $errorBody")
                return@withContext null
            }
            val response = conn.inputStream.bufferedReader().readText()
            val route = org.json.JSONObject(response).getJSONArray("routes").getJSONObject(0)
            val encodedPolyline = route.getJSONObject("polyline").getString("encodedPolyline")
            val distanceMeters = route.optInt("distanceMeters", -1)
            // Duration comes back as a Protobuf Duration string like "812s", not a bare number.
            val durationSeconds = route.optString("duration", "").removeSuffix("s").toDoubleOrNull()?.toInt() ?: -1
            if (distanceMeters < 0 || durationSeconds < 0) return@withContext null
            RouteInfo(decodePolyline(encodedPolyline), distanceMeters, durationSeconds)
        } catch (e: Exception) {
            android.util.Log.e("DermaLens", "Routes API failed", e)
            null
        }
    }
}

/** "2.3 km · 6 min" once the real driving route has resolved; falls back to the straight-line
 *  [Clinic.distance] alone (no duration) before it resolves or if the Routes call failed. */
private fun clinicDistanceLabel(clinic: Clinic, route: RouteInfo?): String {
    if (route == null) return clinic.distance
    val km = "%.1f km".format(route.distanceMeters / 1000.0)
    val minutes = (route.durationSeconds / 60).coerceAtLeast(1)
    return "$km · $minutes min"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClinicLocatorScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMap by remember { mutableStateOf(true) }
    var selectedClinic by remember { mutableStateOf<Clinic?>(null) }
    // The clinic picked on the map (red pin + its route + highlighted card). Separate from
    // selectedClinic, which only drives the details dialog -- tapping a pin shouldn't pop a dialog
    // over the very route it just drew.
    var focusedClinic by remember { mutableStateOf<Clinic?>(null) }
    // Crowd-sourced open/closed tally for whichever clinic's popup is currently open -- see
    // ClinicVotes.kt. Reset per-clinic by the LaunchedEffect below, not shared across clinics.
    var voteTally by remember { mutableStateOf<ClinicVoteTally?>(null) }
    var isVoting by remember { mutableStateOf(false) }
    LaunchedEffect(selectedClinic?.placeId) {
        voteTally = null
        val placeId = selectedClinic?.placeId
        val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (!placeId.isNullOrEmpty() && userId != null) {
            try {
                voteTally = fetchClinicVoteTally(placeId, userId)
            } catch (e: Exception) {
                android.util.Log.e("DermaLens", "Failed to load clinic vote tally", e)
            }
        }
    }
    var clinics by remember { mutableStateOf<List<Clinic>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isOffline by remember { mutableStateOf(false) }
    // Permission granted but a real position still couldn't be determined (GPS/location
    // services off at the OS level, no signal, brand-new device with no cached fix) -- distinct
    // from isOffline (no internet) and from !hasLocationPermission (never asked/denied). Real bug
    // this replaced: silently substituting a hardcoded Tarlac City coordinate whenever
    // FusedLocationProviderClient had no cached lastLocation, which showed clinics near a
    // location that had nothing to do with the actual device.
    var locationUnavailable by remember { mutableStateOf(false) }
    var retryTrigger by remember { mutableStateOf(0) }
    var locationLabel by remember { mutableStateOf("Locating...") }
    var userLat by remember { mutableStateOf(15.4755) }
    var userLng by remember { mutableStateOf(120.5963) }
    var mapCentered by remember { mutableStateOf(false) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    // Real bug this fixed: this screen had no permanently-denied handling at all, unlike
    // CameraScreen's. The "Allow" banner just kept calling permissionLauncher.launch() forever,
    // which silently no-ops once Android stops showing the dialog (after a "Don't allow" that
    // sets shouldShowRequestPermissionRationale to false) -- the button looked broken with zero
    // explanation. Same fix as CameraPermissionDeniedScreen: detect it and offer Open Settings.
    var locationPermanentlyDenied by remember { mutableStateOf(false) }
    val activity = context as? android.app.Activity

    var routes by remember { mutableStateOf<Map<String, RouteInfo>>(emptyMap()) }
    // What the current `routes` map was actually computed against -- lets the effect below tell
    // "clinics changed, must refetch" apart from "position drifted 5 meters since the last GPS
    // tick, don't bother." Google's Routes API is billed per request, unlike the free OSRM demo
    // server this replaced, so re-running it on every single location update (every 3-5s while
    // this screen is open, per the live-tracking DisposableEffect below) would be real cost for
    // no visible benefit -- a driving route's polyline doesn't meaningfully change over 100m.
    var lastRouteFetch by remember { mutableStateOf<Triple<List<Clinic>, Double, Double>?>(null) }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!hasLocationPermission && activity != null) {
            locationPermanentlyDenied = !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else if (hasLocationPermission) {
            locationPermanentlyDenied = false
        }
    }

    fun openAppSettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }

    LaunchedEffect(hasLocationPermission, retryTrigger) {
        isLoading = true
        isOffline = false
        locationUnavailable = false

        if (!isNetworkAvailable(context)) {
            isOffline = true
            isLoading = false
            if (!hasLocationPermission) {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            return@LaunchedEffect
        }

        if (!hasLocationPermission) {
            // Deliberately not showing clinics near a fallback location anymore -- that made the
            // "Location access needed" banner above look wrong (real results showing right below
            // a banner claiming access is needed). Nothing renders until permission is actually
            // granted, so the banner and the empty results screen agree with each other.
            // locationUnavailable = true here too (not just on a failed fetch below) -- otherwise
            // userLat/userLng sit at their hardcoded Tarlac default with nothing marking them as
            // fake, and the marker guard further down draws a false "you are here" dot on the map
            // while this exact banner is telling the user location access is needed.
            locationUnavailable = true
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            locationLabel = "Location unavailable"
            clinics = emptyList()
            isLoading = false
        } else {
            // lastLocation only returns a *cached* fix -- null on a device that's never had one
            // (fresh install, GPS never used, or Google Play Services just hasn't cached
            // anything yet), which is a completely normal, common state, not an edge case. The
            // real bug this replaced: silently falling back to a hardcoded Tarlac City coordinate
            // whenever that cache was empty, showing a real friend's real device clinics near a
            // university city they'd never been to. getCurrentLocation() actively requests a
            // fresh fix instead of trusting a cache that may not exist; lastLocation is still
            // tried first since it's instant when available, with getCurrentLocation only paying
            // its slower cost when there's genuinely nothing cached to use.
            var location = suspendCancellableCoroutine<android.location.Location?> { cont ->
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }
            if (location == null) {
                location = suspendCancellableCoroutine { cont ->
                    val cancellationSource = CancellationTokenSource()
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellationSource.token)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume(null) }
                    cont.invokeOnCancellation { cancellationSource.cancel() }
                }
            }

            if (location == null) {
                // Neither a cached nor a fresh fix was available -- likely location services are
                // off at the OS level (permission alone doesn't guarantee GPS/network location is
                // actually enabled) or there's no signal. Honest empty state, not a guessed
                // coordinate.
                locationUnavailable = true
                locationLabel = "Location unavailable"
                clinics = emptyList()
                isLoading = false
                return@LaunchedEffect
            }

            val lat = location.latitude
            val lng = location.longitude
            userLat = lat
            userLng = lng
            val geocoder = android.location.Geocoder(context)
            val addresses = withContext(Dispatchers.IO) {
                try { geocoder.getFromLocation(lat, lng, 1) } catch (e: Exception) { null }
            }
            locationLabel = addresses?.firstOrNull()?.let {
                listOf(it.subLocality, it.locality, it.adminArea)
                    .filter { s -> !s.isNullOrEmpty() }.take(2).joinToString(", ")
            }?.takeIf { it.isNotEmpty() } ?: "Near you"
            clinics = fetchNearbyClinics(context, lat, lng)
            isLoading = false
        }
    }

    // Live-tracks the "You are here" dot as the device actually moves -- the LaunchedEffect
    // above only ever fetches a position once (on open or Retry), so a real, reported bug was
    // walking around with the screen open never moved the marker at all. Deliberately scoped to
    // just the dot: doesn't re-geocode locationLabel or re-run fetchNearbyClinics on every
    // update, since re-searching Places continuously would cost real API calls and battery for a
    // screen whose actual job (find clinics near where you are right now) is already done by the
    // one-shot fetch above.
    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) {
            return@DisposableEffect onDispose {}
        }
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(3_000L)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                userLat = loc.latitude
                userLng = loc.longitude
                // Real bug this fixed: the one-shot fetch above (LaunchedEffect) is what
                // actually owns locationLabel/clinics/isLoading -- if it already gave up and
                // set locationUnavailable = true, silently clearing that flag here (as this
                // callback used to do) left the screen self-contradictory: a real "You are
                // here" dot appearing right next to text still insisting location couldn't be
                // determined, with clinics never re-searched for. Retrigger the real fetch
                // instead of just patching the flag, so the label/clinics catch up too.
                if (locationUnavailable) {
                    locationUnavailable = false
                    retryTrigger++
                }
            }
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, callback, android.os.Looper.getMainLooper())
        onDispose {
            fusedLocationClient.removeLocationUpdates(callback)
        }
    }

    LaunchedEffect(clinics, userLat, userLng) {
        if (clinics.isEmpty()) {
            routes = emptyMap()
            lastRouteFetch = null
            return@LaunchedEffect
        }
        val last = lastRouteFetch
        val sameClinicSet = last?.first == clinics
        val movedFar = last == null || haversineKm(last.second, last.third, userLat, userLng) > 0.1
        if (sameClinicSet && !movedFar) return@LaunchedEffect

        val fetched = mutableMapOf<String, RouteInfo>()
        clinics.forEach { clinic ->
            fetchRoute(context, userLat, userLng, clinic.lat, clinic.lng)?.let { fetched[clinic.name] = it }
        }
        routes = fetched
        lastRouteFetch = Triple(clinics, userLat, userLng)
    }

    // Offline / no-location / no-results states, shared by the map's sheet and the list view.
    val clinicStatusItems: LazyListScope.() -> Unit = {
        if (!hasLocationPermission) {
            item { LocationPermissionBanner(locationPermanentlyDenied, onAllow = {
                if (locationPermanentlyDenied) openAppSettings()
                else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) }
        }
        item { DiagnosticAidDisclaimer() }
        if (!isLoading && isOffline) {
            item { OfflineClinicsState(onRetry = { retryTrigger++ }, onBackToHome = { navController.popBackStack() }) }
        } else if (!isLoading && locationUnavailable) {
            item {
                OfflineClinicsState(
                    onRetry = { if (locationPermanentlyDenied) openAppSettings() else retryTrigger++ },
                    onBackToHome = { navController.popBackStack() },
                    icon = Icons.Default.LocationOff,
                    title = "Couldn't Determine Your Location",
                    message = if (locationPermanentlyDenied)
                        "Location access was denied and can no longer be requested from within the app. Enable it from Settings to find clinics near you."
                    else
                        "Make sure location services (GPS) are turned on for your device, then retry. This isn't the same as camera or app permissions -- it's a separate system setting.",
                    retryLabel = if (locationPermanentlyDenied) "Open Settings" else "Retry"
                )
            }
        } else if (!isLoading && clinics.isEmpty()) {
            item { EmptyClinicsState() }
        }
    }
    // Number of items clinicStatusItems puts ahead of the first clinic card, for scrolling the
    // sheet to whichever clinic was tapped on the map. The empty/offline states never coexist
    // with real clinics, so only the banner and the disclaimer can sit above them.
    val itemsBeforeClinics = (if (!hasLocationPermission) 1 else 0) + 1

    // Height of the floating back button + Map/List pill row, below the status bar.
    val topControls = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp

    Scaffold(containerColor = DermaPageBackground, bottomBar = { DermaBottomNavBar(navController) }) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding()).clipToBounds().background(DermaPageBackground)
        ) {
            AnimatedContent(
                targetState = showMap,
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.92f)) togetherWith
                        (fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.92f))
                },
                label = "clinicViewToggle"
            ) { targetShowMap ->
            if (targetShowMap) {
                val sheetListState = rememberLazyListState()
                val mapScope = rememberCoroutineScope()
                val cameraPositionState = rememberCameraPositionState {
                    position = CameraPosition.fromLatLngZoom(LatLng(userLat, userLng), 14.5f)
                }
                // BitmapDescriptorFactory can throw if called before the Maps SDK's internal
                // renderer has finished initializing (kicked off in MainActivity.onCreate(),
                // but not guaranteed to have completed yet on a very fast navigation) -- fall
                // back to Maps' default pin rather than crash the screen in that case.
                // Pins share the cards' open/closed/unknown colours; the selected one is just larger,
                // so it doesn't read as a status of its own.
                val markerIcons = remember(context) {
                    listOf(true, false, null).associateWith { openNow ->
                        val color = pinColor(openNow)
                        try {
                            Pair(createMarkerBitmap(context, color), createMarkerBitmap(context, color, selected = true))
                                .let { (plain, focused) ->
                                    Pair(
                                        BitmapDescriptorFactory.fromBitmap(plain.bitmap) to plain.anchorY,
                                        BitmapDescriptorFactory.fromBitmap(focused.bitmap) to focused.anchorY
                                    )
                                }
                        } catch (e: Exception) { null }
                    }
                }
                val userDotIcon = remember(context) {
                    try { BitmapDescriptorFactory.fromBitmap(createUserDotBitmap(context)) } catch (e: Exception) { null }
                }

                // Real bug: userLat/userLng start at a hardcoded default (Tarlac City) before
                // any real fetch resolves, and this effect fires immediately on first
                // composition too -- with those still-default values. That meant it centered
                // on Tarlac and set mapCentered = true before the real location ever arrived,
                // permanently refusing to re-center once it did (guarded by !mapCentered). The
                // "You are here" dot itself would move once real data came in, but the camera
                // view stayed stuck wherever it first happened to fire. Gating on !isLoading
                // ensures this only centers once the initial fetch has actually resolved.
                LaunchedEffect(userLat, userLng, isLoading) {
                    if (!mapCentered && !isLoading) {
                        cameraPositionState.position = CameraPosition.fromLatLngZoom(LatLng(userLat, userLng), 14.5f)
                        mapCentered = true
                    }
                }

                // A tapped pin frames you and that clinic together, and brings its card into view
                // in the sheet so the map and the list agree on which clinic is in focus.
                LaunchedEffect(focusedClinic) {
                    val clinic = focusedClinic ?: return@LaunchedEffect
                    val index = clinics.indexOf(clinic)
                    // Before the camera move, not alongside it -- run concurrently, a long scroll
                    // to a far-down card was getting cut off partway.
                    if (index >= 0) sheetListState.animateScrollToItem(itemsBeforeClinics + index)
                    if (!locationUnavailable) {
                        val bounds = LatLngBounds.builder()
                            .include(LatLng(userLat, userLng))
                            .include(LatLng(clinic.lat, clinic.lng))
                            .build()
                        try {
                            cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 160))
                        } catch (e: IllegalStateException) {
                            // Map not laid out yet -- the route still draws, just without re-framing.
                        }
                    }
                }

                BottomSheetScaffold(
                    // The sheet is taller than the space above the tab bar; clip it so the hidden
                    // part doesn't show through below the bar.
                    modifier = Modifier.clipToBounds(),
                    sheetPeekHeight = CLINIC_SHEET_PEEK,
                    sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    sheetContainerColor = DermaPageBackground,
                    sheetShadowElevation = 12.dp,
                    sheetContent = {
                        ClinicListHeader(locationLabel = locationLabel, isLoading = isLoading, clinicCount = clinics.size)
                        LazyColumn(
                            state = sheetListState,
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            clinicStatusItems()
                            itemsIndexed(clinics) { index, clinic ->
                                val card = @Composable {
                                    CompactClinicCard(
                                        clinic = clinic,
                                        route = routes[clinic.name],
                                        selected = clinic == focusedClinic,
                                        onClick = { focusedClinic = clinic; selectedClinic = clinic }
                                    )
                                }
                                // Only the cards on screen at open get the entrance animation: it
                                // starts each card at zero height, so cards further down -- first
                                // composed mid-scroll when a far pin is tapped -- would grow in after
                                // the scroll lands and push the tapped clinic's card back out of view.
                                if (index < 6) EntranceAnimation(delayMillis = index * 60) { card() } else card()
                            }
                        }
                    }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        GoogleMap(
                            modifier = Modifier.fillMaxSize(),
                            cameraPositionState = cameraPositionState,
                            // Keeps centering/framing inside the strip the sheet and toggle leave visible.
                            contentPadding = PaddingValues(top = topControls, bottom = CLINIC_SHEET_PEEK),
                            uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false),
                            onMapClick = { focusedClinic = null }
                        ) {
                            // Real bug, same family as the earlier hardcoded-Tarlac fallback: when
                            // location genuinely can't be determined, userLat/userLng just sit at
                            // their initial default value (Tarlac City) since nothing ever assigns
                            // them in the failure path. Unconditionally drawing a "You are here" dot
                            // there claimed a location the app had just said, one card below, that it
                            // didn't actually know.
                            if (!locationUnavailable) {
                                Marker(
                                    state = MarkerState(position = LatLng(userLat, userLng)),
                                    title = "You are here",
                                    icon = userDotIcon
                                )
                            }
                            // Only the clinic in focus gets a route -- twenty overlapping lines at
                            // once read as noise rather than directions.
                            focusedClinic?.let { clinic ->
                                val routePoints = routes[clinic.name]?.points
                                    ?: listOf(LatLng(userLat, userLng), LatLng(clinic.lat, clinic.lng))
                                Polyline(points = routePoints, color = Color(0xFF7C3AED), width = 10f)
                            }
                            clinics.forEach { clinic ->
                                val pinIcon = markerIcons[clinic.openNow]?.let { if (clinic == focusedClinic) it.second else it.first }
                                Marker(
                                    state = MarkerState(position = LatLng(clinic.lat, clinic.lng)),
                                    title = clinic.name,
                                    snippet = clinic.address,
                                    icon = pinIcon?.first,
                                    anchor = Offset(0.5f, pinIcon?.second ?: 1f),
                                    zIndex = if (clinic == focusedClinic) 1f else 0f,
                                    onClick = { focusedClinic = clinic; true }
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = topControls, end = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MapZoomButton(Icons.Default.Add, "Zoom in") {
                                mapScope.launch { cameraPositionState.animate(CameraUpdateFactory.zoomIn()) }
                            }
                            MapZoomButton(Icons.Default.Remove, "Zoom out") {
                                mapScope.launch { cameraPositionState.animate(CameraUpdateFactory.zoomOut()) }
                            }
                        }
                        // Routes only draw for the tapped pin, so say so -- otherwise the map looks
                        // like it simply has no directions.
                        androidx.compose.animation.AnimatedVisibility(
                            visible = focusedClinic == null && clinics.isNotEmpty(),
                            enter = fadeIn(tween(250)),
                            exit = fadeOut(tween(200)),
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = CLINIC_SHEET_PEEK + 16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .shadow(6.dp, RoundedCornerShape(50))
                                    .background(Color(0xFF1F2937), RoundedCornerShape(50))
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.TouchApp, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Tap a pin to see its route", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            }
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(top = topControls)) {
                    ClinicListHeader(locationLabel = locationLabel, isLoading = isLoading, clinicCount = clinics.size)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        clinicStatusItems()
                        itemsIndexed(clinics) { index, clinic ->
                            EntranceAnimation(delayMillis = index.coerceAtMost(6) * 60) {
                                FullClinicCard(clinic = clinic, route = routes[clinic.name], onClick = { focusedClinic = clinic; selectedClinic = clinic })
                            }
                        }
                    }
                }
            }
            }

            // Floating controls: round back button + Map/List pill
            Box(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.align(Alignment.CenterStart)) {
                    RoundIconButton(icon = Icons.Default.ArrowBack, contentDescription = "Go back", onClick = { navController.popBackStack() })
                }
                MapListToggle(showMap = showMap, onChange = { showMap = it })
            }
        }
    }

    selectedClinic?.let { clinic ->
        DermaAlertDialog(
            onDismissRequest = { selectedClinic = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ClinicIconTile(size = 44.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(clinic.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp)
                }
            },
            text = {
                Column {
                    DetailRow(icon = Icons.Default.LocationOn, text = clinic.address)
                    DetailRow(icon = Icons.Default.AccessTime, text = clinic.hours)
                    DetailRow(icon = Icons.Default.Phone, text = clinic.phone)
                    val status = openStatus(clinic.openNow)
                    DetailRow(icon = Icons.Default.Circle, text = status.label, textColor = status.textColor)

                    // Crowd-sourced signal, separate from Google's own businessStatus/hours
                    // above -- see ClinicVotes.kt for why (a specific branch can go stale on
                    // Google's side well before Google's own data reflects it).
                    if (clinic.placeId.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = Color(0xFFF3F4F6))
                        Text("Still open? Help others by confirming.", fontSize = 12.sp, color = DermaSubtle)
                        Spacer(modifier = Modifier.height(8.dp))
                        val tally = voteTally
                        if (tally == null) {
                            CircularProgressIndicator(color = DermaGreen, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            fun castVote(choice: String) {
                                val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
                                isVoting = true
                                scope.launch {
                                    try {
                                        voteTally = castClinicVote(clinic.placeId, userId, choice)
                                    } catch (e: Exception) {
                                        android.util.Log.e("DermaLens", "Clinic vote failed", e)
                                    }
                                    isVoting = false
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                VoteChip(
                                    label = "Still open (${tally.confirmedOpenCount})",
                                    selected = tally.myVote == "open",
                                    selectedColor = DermaSuccess,
                                    enabled = !isVoting,
                                    onClick = { castVote("open") }
                                )
                                VoteChip(
                                    label = "Closed (${tally.confirmedClosedCount})",
                                    selected = tally.myVote == "closed",
                                    selectedColor = DermaDanger,
                                    enabled = !isVoting,
                                    onClick = { castVote("closed") }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { selectedClinic = null }, colors = ButtonDefaults.buttonColors(containerColor = DermaGreen), shape = RoundedCornerShape(50)) {
                    Text("Close", fontWeight = FontWeight.SemiBold)
                }
            },
            // Google's own index can be stale for a specific branch even when nothing about the
            // request is wrong on this app's end (a whole chain shows "operational" because most
            // branches are, even if this one specifically closed -- see the businessStatus filter
            // above for what that *can* catch, and why this can't be caught the same way). Opens
            // this exact clinic's real Maps page (by Place ID, not a name search -- chains like
            // this one have many identically-named branches) so anyone can flag it there, which
            // fixes it for every future user of Maps, not just this app.
            dismissButton = if (clinic.placeId.isNotEmpty()) {
                {
                    TextButton(onClick = {
                        // Google's Maps URLs API (https://developers.google.com/maps/documentation/urls/get-started)
                        // needs *both* params -- query_place_id alone with no query is documented
                        // but q=place_id:<id> (what this used to send) isn't a real scheme at all
                        // and Maps just treats it as literal search text instead of a place lookup.
                        val encodedName = java.net.URLEncoder.encode(clinic.name, "UTF-8")
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.google.com/maps/search/?api=1&query=$encodedName&query_place_id=${clinic.placeId}")
                        )
                        context.startActivity(intent)
                    }) {
                        Text("Report incorrect info", color = DermaSubtle, fontSize = 12.sp)
                    }
                }
            } else null,
            shape = RoundedCornerShape(28.dp),
            containerColor = Color.White,
            titleContentColor = Color(0xFF111827),
            textContentColor = Color(0xFF374151)
        )
    }
}

@Composable
fun OfflineClinicsState(
    onRetry: () -> Unit,
    onBackToHome: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.WifiOff,
    title: String = "No Internet Connection",
    message: String = "The clinic locator requires an internet connection to find nearby dermatology clinics and get real-time information.",
    retryLabel: String = "Retry"
) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFF3F4F6)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = DermaSubtle, modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1a1a1a))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                message,
                fontSize = 12.sp,
                color = Color.Gray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            PillButton(text = retryLabel, elevated = true, height = 48.dp, onClick = onRetry)
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(onClick = onBackToHome) {
                Text("Back to Home", color = DermaSubtle)
            }
        }
    }
}

@Composable
fun EmptyClinicsState() {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(DermaGreenLight), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.LocationOff, contentDescription = null, tint = DermaGreen, modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text("No dermatology clinics found nearby", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1a1a1a))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "We couldn't find any clinics within 15 km of your location. Try again later or search a different area.",
                fontSize = 12.sp,
                color = Color.Gray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/** Rounded-square tile with a medical cross, the clinic's icon throughout this screen. */
@Composable
private fun ClinicIconTile(size: androidx.compose.ui.unit.Dp = 44.dp) {
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(DermaGreenLight),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Outlined.LocalHospital, contentDescription = null, tint = DermaGreen, modifier = Modifier.size(size * 0.52f))
    }
}

/** Small status label: coloured dot + "Open" / "Closed" / "Hours unknown". */
@Composable
private fun StatusPill(openNow: Boolean?, short: Boolean) {
    val status = openStatus(openNow)
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(status.background).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(status.textColor))
        Spacer(modifier = Modifier.width(5.dp))
        Text(if (short) status.shortLabel else status.label, fontSize = 11.sp, color = status.textColor, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun CompactClinicCard(clinic: Clinic, route: RouteInfo? = null, selected: Boolean = false, onClick: () -> Unit) {
    val settings = LocalAppSettings.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(if (selected) 0.dp else 8.dp, shape, ambientColor = Color(0x1A4C1D95), spotColor = Color(0x1A4C1D95))
            .clip(shape)
            .background(if (selected) Color(0xFFF5F3FF) else Color.White)
            .then(if (selected) Modifier.border(2.dp, DermaGreen, shape) else Modifier)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ClinicIconTile(size = 44.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(clinic.name, fontSize = settings.textMd.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827), maxLines = 2, lineHeight = TextUnit.Unspecified)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "${clinicDistanceLabel(clinic, route)} · ${todaysHoursLine(clinic.hours)}",
                fontSize = settings.textBase.sp,
                color = DermaSubtle,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        StatusPill(clinic.openNow, short = true)
    }
}

@Composable
fun FullClinicCard(clinic: Clinic, route: RouteInfo? = null, onClick: () -> Unit) {
    val settings = LocalAppSettings.current
    SoftCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClinicIconTile(size = 48.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(clinic.name, fontSize = settings.textLg.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827), lineHeight = TextUnit.Unspecified)
                    Text(clinic.address, fontSize = settings.textBase.sp, color = DermaSubtle, lineHeight = TextUnit.Unspecified)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFF3F4F6)).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = DermaSubtle, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(clinicDistanceLabel(clinic, route), fontSize = settings.textBase.sp, color = Color(0xFF4B5563))
                }
                StatusPill(clinic.openNow, short = false)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccessTime, contentDescription = null, tint = DermaMuted, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(todaysHoursLine(clinic.hours), fontSize = settings.textBase.sp, color = DermaSubtle, maxLines = 1)
            }
        }
    }
}

private data class OpenStatus(val label: String, val shortLabel: String, val textColor: Color, val background: Color)

private fun openStatus(openNow: Boolean?) = when (openNow) {
    true -> OpenStatus("Open Now", "Open", if (ContrastMode.highContrast) Color(0xFF14532D) else Color(0xFF2E7D32), Color(0xFFE8F5E9))
    false -> OpenStatus("Closed", "Closed", if (ContrastMode.highContrast) Color(0xFF7F1D1D) else Color(0xFFC62828), Color(0xFFFFEBEE))
    null -> OpenStatus("Hours unknown", "Hours unknown", DermaSubtle, Color(0xFFF3F4F6))
}

/** How much of the clinic sheet shows over the map before it's dragged up. */
private val CLINIC_SHEET_PEEK = 340.dp

/** Floating "Map View | List View" pill -- the one switch between the two layouts. */
@Composable
fun MapListToggle(showMap: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(50))
            .background(Color.White, RoundedCornerShape(50))
            .padding(4.dp)
    ) {
        listOf(true to "Map View", false to "List View").forEach { (isMap, label) ->
            val active = showMap == isMap
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) DermaGreen else Color.Transparent)
                    .clickable { onChange(isMap) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isMap) Icons.Default.Map else Icons.Default.List,
                    contentDescription = null,
                    tint = if (active) Color.White else DermaSubtle,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (active) Color.White else DermaSubtle)
            }
        }
    }
}

@Composable
fun ClinicListHeader(locationLabel: String, isLoading: Boolean, clinicCount: Int) {
    val settings = LocalAppSettings.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Nearby Dermatology Clinics", fontSize = settings.textLg.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1a1a1a))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = DermaGreen, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(locationLabel, fontSize = settings.textBase.sp, color = DermaSubtle, maxLines = 1)
            }
        }
        Box(modifier = Modifier.background(DermaGreenLight, RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
            if (isLoading) {
                CircularProgressIndicator(color = DermaGreen, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            } else {
                Text("$clinicCount found", fontSize = settings.textBase.sp, color = DermaGreen, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun MapZoomButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .shadow(8.dp, CircleShape, ambientColor = Color(0x334C1D95), spotColor = Color(0x334C1D95))
            .clip(CircleShape)
            .background(Color.White)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = DermaGreen, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun LocationPermissionBanner(permanentlyDenied: Boolean, onAllow: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.LocationOff, contentDescription = null, tint = Color(0xFFE65100), modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Location access needed", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                Text(
                    if (permanentlyDenied) "Enable it from Settings to find clinics near you" else "Enable location to find clinics near you",
                    fontSize = 12.sp, color = Color(0xFFE65100)
                )
            }
            TextButton(onClick = onAllow) {
                Text(if (permanentlyDenied) "Open Settings" else "Allow", color = DermaGreen, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun VoteChip(label: String, selected: Boolean, selectedColor: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) selectedColor.copy(alpha = 0.15f) else Color(0xFFF3F4F6))
            .then(if (selected) Modifier.border(1.dp, selectedColor, RoundedCornerShape(20.dp)) else Modifier)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) selectedColor else Color(0xFF374151)
        )
    }
}

@Composable
fun DetailRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, textColor: Color = Color(0xFF444444)) {
    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = DermaGreen, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = textColor)
    }
}