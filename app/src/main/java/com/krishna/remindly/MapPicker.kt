package com.krishna.remindly

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority as GmsPriority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polygon

/**
 * v2.02 (N33) map picker. Provider = Google Maps SDK when Krishna's rule allows (Geo.mapProviderNow),
 * else OpenStreetMap via osmdroid — the badge always names the live provider and the fallback reason.
 * C2: opens CENTRED ON THE DEVICE'S CURRENT GPS FIX when there is no existing pin (order: existing
 * pin → GPS fix → last known → city centre), with a "Current GPS location" button; a search field
 * geocodes through Geo.lookup (Google → OSM fallback); the geofence radius is chosen from the
 * 13-stop dropdown and drawn as a circle. Every location/geocode call is guarded and logged.
 */
private const val FALLBACK_LAT = 22.5726
private const val FALLBACK_LNG = 88.3639

@Composable
fun RadiusDropdown(value: Float, accent: Color, label: String = "Geofence radius", onChange: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) {
                Text(radiusLabel(value), color = InkPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("▾", color = InkHint)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                RADIUS_STOPS.forEach { stop ->
                    DropdownMenuItem(
                        text = { Text(radiusLabel(stop), fontWeight = if (stop == snapRadius(value)) FontWeight.Bold else FontWeight.Normal,
                            color = if (stop == snapRadius(value)) accent else InkPrimary) },
                        onClick = { onChange(stop); open = false }
                    )
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun MapPickerDialog(
    initialLat: Double?,
    initialLng: Double?,
    accent: Color,
    radius: Float? = null,
    onRadius: ((Float) -> Unit)? = null,
    onDismiss: () -> Unit,
    onPick: (Double, Double) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasExisting = initialLat != null && initialLng != null
    var picked by remember { mutableStateOf<Pair<Double, Double>?>(if (hasExisting) initialLat!! to initialLng!! else null) }
    var myPos by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var status by remember { mutableStateOf(if (hasExisting) "Editing the saved pin" else "Finding your position…") }
    var busy by remember { mutableStateOf(!hasExisting) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val provider = remember { Geo.mapProviderNow(context) }
    val reason = remember { Geo.fallbackReason(context, forMap = true) }
    var camTarget by remember { mutableStateOf<Pair<Double, Double>>((initialLat ?: FALLBACK_LAT) to (initialLng ?: FALLBACK_LNG)) }
    var camTick by remember { mutableStateOf(0) }
    val hasPerm = remember { Geofencer.hasFineLocation(context) }

    fun goTo(lat: Double, lng: Double, dropPin: Boolean) { camTarget = lat to lng; camTick++; if (dropPin) picked = lat to lng }

    suspend fun locate(dropPin: Boolean) {
        busy = true
        runCatching {
            if (!hasPerm) { status = "Location permission not granted — showing the last area"; return }
            val fused = LocationServices.getFusedLocationProviderClient(context)
            val loc = runCatching { fused.getCurrentLocation(GmsPriority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await() }.getOrNull()
                ?: runCatching { fused.lastLocation.await() }.getOrNull()
            if (loc != null) {
                myPos = loc.latitude to loc.longitude
                goTo(loc.latitude, loc.longitude, dropPin)
                status = "Your position (±${loc.accuracy.toInt()} m)" + if (dropPin) " — pin placed here" else ""
            } else status = "Couldn't get a GPS fix — showing the last area"
        }.onFailure { Logger.e(context, "GEO", it, "picker GPS fix failed"); status = "Couldn't get a GPS fix — showing the last area" }
        busy = false
    }
    LaunchedEffect(Unit) { if (!hasExisting) locate(dropPin = true) else runCatching { locate(dropPin = false) } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = SurfaceCard, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Pick the place", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = InkPrimary)
                    Text("Tap the map to move the pin. " + status, style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text("Search address…") }, modifier = Modifier.weight(1f)
                        )
                        IconButton(enabled = query.isNotBlank() && !searching, onClick = {
                            searching = true
                            scope.launch {
                                val hit = runCatching { Geo.lookup(context, query) }.getOrNull()
                                searching = false
                                if (hit != null) { goTo(hit.lat, hit.lng, dropPin = true); status = "Found via ${if (hit.provider == PROVIDER_GOOGLE) "Google" else "OSM"}: ${hit.label}" }
                                else status = "No match for “${query.trim()}”"
                            }
                        }) { if (searching) CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Search, "Search", tint = accent) }
                    }
                }

                Box(Modifier.weight(1f)) {
                    if (provider == PROVIDER_GOOGLE) {
                        val cam = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(camTarget.first, camTarget.second), 16f) }
                        LaunchedEffect(camTick) { runCatching { cam.animate(CameraUpdateFactory.newLatLngZoom(LatLng(camTarget.first, camTarget.second), 16f)) } }
                        GoogleMap(
                            modifier = Modifier.fillMaxSize(),
                            cameraPositionState = cam,
                            properties = MapProperties(isMyLocationEnabled = hasPerm),
                            uiSettings = MapUiSettings(myLocationButtonEnabled = false, zoomControlsEnabled = false, mapToolbarEnabled = false),
                            onMapClick = { picked = it.latitude to it.longitude }
                        ) {
                            picked?.let { p ->
                                Marker(state = MarkerState(position = LatLng(p.first, p.second)), title = "Selected place")
                                if (radius != null) Circle(center = LatLng(p.first, p.second), radius = radius.toDouble(),
                                    fillColor = accent.copy(alpha = 0.15f), strokeColor = accent, strokeWidth = 3f)
                            }
                        }
                    } else {
                        var mapRef by remember { mutableStateOf<MapView?>(null) }
                        val markerRef = remember { mutableStateOf<org.osmdroid.views.overlay.Marker?>(null) }
                        val circleRef = remember { mutableStateOf<Polygon?>(null) }
                        val meRef = remember { mutableStateOf<org.osmdroid.views.overlay.Marker?>(null) }
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                Configuration.getInstance().userAgentValue = ctx.packageName
                                val map = MapView(ctx)
                                map.setTileSource(TileSourceFactory.MAPNIK)
                                map.setMultiTouchControls(true)
                                map.controller.setZoom(16.0)
                                map.controller.setCenter(GeoPoint(camTarget.first, camTarget.second))
                                val marker = org.osmdroid.views.overlay.Marker(map).apply {
                                    setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                                    title = "Selected place"
                                }
                                val circle = Polygon(map).apply { fillPaint.color = 0x2600B8A9; outlinePaint.color = 0xFF0E9F6E.toInt(); outlinePaint.strokeWidth = 3f }
                                val me = org.osmdroid.views.overlay.Marker(map).apply {
                                    setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
                                    title = "You are here"
                                }
                                map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                                        if (p != null) picked = p.latitude to p.longitude
                                        return true
                                    }
                                    override fun longPressHelper(p: GeoPoint?): Boolean = false
                                }))
                                map.overlays.add(circle); map.overlays.add(marker); map.overlays.add(me)
                                markerRef.value = marker; circleRef.value = circle; meRef.value = me
                                mapRef = map
                                map
                            },
                            update = { map ->
                                runCatching {
                                    val p = picked
                                    val marker = markerRef.value; val circle = circleRef.value
                                    if (p != null && marker != null) {
                                        marker.position = GeoPoint(p.first, p.second)
                                        marker.setVisible(true)
                                        if (radius != null && circle != null) { circle.points = Polygon.pointsAsCircle(GeoPoint(p.first, p.second), radius.toDouble()) }
                                    } else marker?.setVisible(false)
                                    val me = meRef.value; val mp = myPos
                                    if (me != null) { if (mp != null) { me.position = GeoPoint(mp.first, mp.second); me.setVisible(true) } else me.setVisible(false) }
                                    map.invalidate()
                                }
                            }
                        )
                        LaunchedEffect(camTick) { runCatching { mapRef?.controller?.animateTo(GeoPoint(camTarget.first, camTarget.second)) } }
                    }
                    // provider badge — never silent about a fallback
                    Text(
                        (if (provider == PROVIDER_GOOGLE) "Google · Maps SDK" else "OSM") + (reason?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (provider == PROVIDER_GOOGLE) Color(0xFF1A73E8) else Color(0xFF7B5E2B)).padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                Surface(shadowElevation = 10.dp, color = Color.White) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).navigationBarsPadding()) {
                        OutlinedButton(onClick = { scope.launch { locate(dropPin = true) } }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Filled.MyLocation, null, tint = accent); Spacer(Modifier.width(6.dp))
                            Text("Current GPS location", color = accent, fontWeight = FontWeight.SemiBold)
                        }
                        if (radius != null && onRadius != null) {
                            Spacer(Modifier.height(6.dp))
                            RadiusDropdown(value = radius, accent = accent) { onRadius(it) }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(picked?.let { "Pin: %.5f, %.5f".format(it.first, it.second) } ?: "No pin yet — tap the map or use your GPS location",
                            style = MaterialTheme.typography.labelMedium, color = InkSubtle)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp)) { Text("Cancel", fontWeight = FontWeight.Bold) }
                            Button(
                                enabled = picked != null,
                                onClick = { picked?.let { onPick(it.first, it.second) } },
                                colors = ButtonDefaults.buttonColors(containerColor = accent),
                                modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp)
                            ) { Text("Use this spot", color = Color.White, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}
