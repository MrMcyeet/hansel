package dev.hansel.map

import android.annotation.SuppressLint
import android.graphics.PointF
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hansel.model.GeoMath
import dev.hansel.model.LatLng
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * MapLibre map as a composable. The map is a long-lived Android view; overlays and camera are driven from Compose
 * state. Drag-to-shape intercepts touches that start on the selected route (when [MapGestures.dragEnabled]).
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun HanselMap(
    styleUrl: String,
    overlay: MapOverlay,
    cameraMode: CameraMode,
    gestures: MapGestures,
    modifier: Modifier = Modifier,
    /** Pixels reserved at the bottom (sheet/cards) so the follow camera keeps the puck visible. */
    bottomInsetPx: Int = 0,
    onControllerReady: (MapController) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentGestures by rememberUpdatedState(gestures)
    val currentOverlay by rememberUpdatedState(overlay)
    var controller by remember { mutableStateOf<MapController?>(null) }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context, MapLibreMapOptions.createFromAttributes(context).apply {
            textureMode(true) // plays better inside Compose/AndroidView and with sheets drawn over it
            attributionEnabled(false)
            logoEnabled(false)
        })
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = {
            mapView.getMapAsync { map ->
                val c = MapController(map)
                controller = c
                map.uiSettings.isRotateGesturesEnabled = true
                map.uiSettings.isCompassEnabled = false
                map.setStyle(styleUrl) { style -> c.onStyleLoaded(style); onControllerReady(c) }
                map.addOnMapClickListener { ll ->
                    val pt = map.projection.toScreenLocation(ll)
                    val hits = map.queryRenderedFeatures(pt, *MapController.ROUTE_LAYERS)
                    val id = hits.firstOrNull()?.getStringProperty("routeId")
                    if (id != null) { currentGestures.onRouteTap(id); true } else { currentGestures.onMapTap(LatLng(ll.latitude, ll.longitude)); false }
                }
                map.addOnMapLongClickListener { ll -> currentGestures.onLongPress(LatLng(ll.latitude, ll.longitude)); true }
                map.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) currentGestures.onUserMovedCamera()
                }
                installDragInterceptor(mapView, map, c, { currentGestures }, { currentOverlay })
            }
            mapView
        },
        modifier = modifier,
        update = { },
    )

    // Style changes (theme/key) after first load.
    LaunchedEffect(styleUrl, controller) {
        val c = controller ?: return@LaunchedEffect
        if (c.map.style?.uri != styleUrl) c.map.setStyle(styleUrl) { style -> c.onStyleLoaded(style) }
    }
    LaunchedEffect(overlay, controller) { controller?.apply(overlay) }
    LaunchedEffect(cameraMode, overlay.routes, overlay.selectedRouteId, overlay.snapped, overlay.position, controller) {
        val c = controller ?: return@LaunchedEffect
        when (cameraMode) {
            CameraMode.FREE -> Unit
            CameraMode.FIT_ROUTES -> c.fitRoutes(overlay, paddingPx = 140)
            CameraMode.CENTER_POSITION -> overlay.position?.point?.let { c.centerOn(it, 14.5) }
            CameraMode.FOLLOW -> {
                val p = overlay.snapped ?: overlay.position?.point ?: return@LaunchedEffect
                val bearing = overlay.position?.bearing?.toDouble()?.takeIf { (overlay.position?.speedMps ?: 0f) > 1.5f }
                c.follow(p, bearing, bottomOffsetPx = bottomInsetPx)
            }
        }
    }
}

/**
 * Touch interceptor for drag-to-shape. On DOWN near the selected route (and only when dragging is enabled) we take
 * over the gesture: MapLibre never sees it, so the map does not pan. On UP we hand (grab, drop) to the host. A DOWN/UP
 * with no real movement is treated as a tap on the route.
 */
private fun installDragInterceptor(
    mapView: MapView, map: MapLibreMap, controller: MapController,
    gestures: () -> MapGestures, overlay: () -> MapOverlay,
) {
    val slop = ViewConfiguration.get(mapView.context).scaledTouchSlop.toFloat()
    val grabPx = 28f * mapView.resources.displayMetrics.density
    var dragging = false
    var moved = false
    var grab: LatLng? = null
    var downX = 0f; var downY = 0f

    mapView.setOnTouchListener { _, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false; moved = false; grab = null
                val g = gestures()
                val route = overlay().selectedRoute
                if (!g.dragEnabled || route == null || ev.pointerCount > 1) return@setOnTouchListener false
                val ll = map.projection.fromScreenLocation(PointF(ev.x, ev.y))
                val p = LatLng(ll.latitude, ll.longitude)
                val proj = GeoMath.projectOntoPolyline(p, route.geometry) ?: return@setOnTouchListener false
                val metersPerPx = map.projection.getMetersPerPixelAtLatitude(p.lat)
                if (proj.distanceMeters <= grabPx * metersPerPx) {
                    dragging = true; grab = proj.point; downX = ev.x; downY = ev.y
                    controller.setDragPreview(proj.point)
                    true
                } else false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return@setOnTouchListener false
                if (!moved && hypot(ev.x - downX, ev.y - downY) > slop) moved = true
                val ll = map.projection.fromScreenLocation(PointF(ev.x, ev.y))
                controller.setDragPreview(LatLng(ll.latitude, ll.longitude))
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return@setOnTouchListener false
                dragging = false
                controller.setDragPreview(null)
                val g = grab ?: return@setOnTouchListener true
                if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    val ll = map.projection.fromScreenLocation(PointF(ev.x, ev.y))
                    val drop = LatLng(ll.latitude, ll.longitude)
                    if (moved && abs(g.distanceTo(drop)) > 5.0) gestures().onDragShaping(g, drop)
                    else overlay().selectedRoute?.let { gestures().onRouteTap(it.id) }
                }
                true
            }
            else -> false
        }
    }
}
