package dev.hansel.map

import android.graphics.Color
import com.google.gson.JsonObject
import dev.hansel.model.GeoMath
import dev.hansel.model.LatLng
import dev.hansel.model.WaypointType
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import org.maplibre.android.geometry.LatLng as MlLatLng

/**
 * Owns the Hansel sources/layers on a loaded [Style] and knows how to push a [MapOverlay] into them. The Compose
 * wrapper creates one per MapView and calls [apply] on every overlay change.
 */
class MapController(val map: MapLibreMap) {
    private var style: Style? = null
    private var lastOverlay: MapOverlay? = null

    val isReady: Boolean get() = style?.isFullyLoaded == true

    fun onStyleLoaded(style: Style) {
        this.style = style
        installLayers(style)
        lastOverlay?.let { apply(it, force = true) }
    }

    private fun installLayers(style: Style) {
        fun src(id: String) { if (style.getSource(id) == null) style.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList()))) }
        listOf(SRC_ROUTES, SRC_POINTS, SRC_ZONES, SRC_POSITION, SRC_DRAG).forEach(::src)

        val selected = Expression.eq(Expression.get("selected"), Expression.literal(true))
        val notSelected = Expression.neq(Expression.get("selected"), Expression.literal(true))

        if (style.getLayer(L_ZONES_FILL) == null) style.addLayer(FillLayer(L_ZONES_FILL, SRC_ZONES).withProperties(
            PropertyFactory.fillColor(Color.parseColor("#E5484D")), PropertyFactory.fillOpacity(0.22f)))
        if (style.getLayer(L_ZONES_LINE) == null) style.addLayer(LineLayer(L_ZONES_LINE, SRC_ZONES).withProperties(
            PropertyFactory.lineColor(Color.parseColor("#E5484D")), PropertyFactory.lineWidth(2f), PropertyFactory.lineDasharray(arrayOf(2f, 2f))))

        if (style.getLayer(L_ROUTES_ALT) == null) style.addLayer(LineLayer(L_ROUTES_ALT, SRC_ROUTES).withProperties(
            PropertyFactory.lineColor(Color.parseColor("#7C8797")), PropertyFactory.lineWidth(5f), PropertyFactory.lineOpacity(0.85f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)).apply { setFilter(notSelected) })
        if (style.getLayer(L_ROUTE_CASING) == null) style.addLayer(LineLayer(L_ROUTE_CASING, SRC_ROUTES).withProperties(
            PropertyFactory.lineColor(Color.parseColor("#0B3B33")), PropertyFactory.lineWidth(11f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)).apply { setFilter(selected) })
        if (style.getLayer(L_ROUTE) == null) style.addLayer(LineLayer(L_ROUTE, SRC_ROUTES).withProperties(
            PropertyFactory.lineColor(Color.parseColor("#3DDCB4")), PropertyFactory.lineWidth(7f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)).apply { setFilter(selected) })

        // Points: stops (large white), through/shaping (small teal), lock anchors (tiny amber), destination flag (red)
        if (style.getLayer(L_POINTS) == null) style.addLayer(CircleLayer(L_POINTS, SRC_POINTS).withProperties(
            PropertyFactory.circleRadius(Expression.match(Expression.get("kind"),
                Expression.literal(5f),
                Expression.stop("stop", 8f), Expression.stop("through", 6f), Expression.stop("anchor", 3.5f), Expression.stop("destination", 9f))),
            PropertyFactory.circleColor(Expression.match(Expression.get("kind"),
                Expression.color(Color.WHITE),
                Expression.stop("stop", Expression.color(Color.WHITE)),
                Expression.stop("through", Expression.color(Color.parseColor("#3DDCB4"))),
                Expression.stop("anchor", Expression.color(Color.parseColor("#F5B547"))),
                Expression.stop("destination", Expression.color(Color.parseColor("#E5484D"))))),
            PropertyFactory.circleStrokeColor(Color.parseColor("#0E1116")), PropertyFactory.circleStrokeWidth(2f)))

        if (style.getLayer(L_POSITION_HALO) == null) style.addLayer(CircleLayer(L_POSITION_HALO, SRC_POSITION).withProperties(
            PropertyFactory.circleRadius(Expression.get("accuracyPx")), PropertyFactory.circleColor(Color.parseColor("#3DDCB4")), PropertyFactory.circleOpacity(0.15f)))
        if (style.getLayer(L_POSITION) == null) style.addLayer(CircleLayer(L_POSITION, SRC_POSITION).withProperties(
            PropertyFactory.circleRadius(9f), PropertyFactory.circleColor(Color.parseColor("#FFFFFF")),
            PropertyFactory.circleStrokeColor(Color.parseColor("#3DDCB4")), PropertyFactory.circleStrokeWidth(4f)))
        if (style.getLayer(L_DRAG) == null) style.addLayer(CircleLayer(L_DRAG, SRC_DRAG).withProperties(
            PropertyFactory.circleRadius(11f), PropertyFactory.circleColor(Color.parseColor("#3DDCB4")),
            PropertyFactory.circleStrokeColor(Color.WHITE), PropertyFactory.circleStrokeWidth(3f), PropertyFactory.circleOpacity(0.9f)))
    }

    fun apply(overlay: MapOverlay, force: Boolean = false) {
        val st = style ?: run { lastOverlay = overlay; return }
        val prev = lastOverlay
        lastOverlay = overlay
        if (force || prev?.routes !== overlay.routes || prev.selectedRouteId != overlay.selectedRouteId) {
            val selectedId = overlay.selectedRoute?.id
            // Draw alternatives first so the selected route renders on top within its own layer ordering.
            val feats = overlay.routes.sortedBy { it.id == selectedId }.map { r ->
                Feature.fromGeometry(LineString.fromLngLats(r.geometry.map { Point.fromLngLat(it.lon, it.lat) }), JsonObject().apply {
                    addProperty("routeId", r.id); addProperty("selected", r.id == selectedId); addProperty("index", r.alternativeIndex)
                })
            }
            st.getSourceAs<GeoJsonSource>(SRC_ROUTES)?.setGeoJson(FeatureCollection.fromFeatures(feats))
        }
        if (force || prev?.waypoints !== overlay.waypoints || prev.locks !== overlay.locks || prev.destination != overlay.destination) {
            val feats = ArrayList<Feature>()
            overlay.waypoints.forEachIndexed { i, wp ->
                val isEnd = i == overlay.waypoints.lastIndex
                val kind = when {
                    isEnd -> "destination"
                    i == 0 -> return@forEachIndexed // origin is the position puck
                    wp.type == WaypointType.STOP || wp.type == WaypointType.STOP_THROUGH -> "stop"
                    else -> "through"
                }
                feats += Feature.fromGeometry(Point.fromLngLat(wp.point.lon, wp.point.lat), JsonObject().apply { addProperty("kind", kind); addProperty("id", wp.id) })
            }
            overlay.locks.forEach { lock -> lock.anchors.forEach { a ->
                feats += Feature.fromGeometry(Point.fromLngLat(a.lon, a.lat), JsonObject().apply { addProperty("kind", "anchor"); addProperty("id", lock.id) })
            } }
            if (overlay.waypoints.isEmpty()) overlay.destination?.let { d ->
                feats += Feature.fromGeometry(Point.fromLngLat(d.lon, d.lat), JsonObject().apply { addProperty("kind", "destination") })
            }
            st.getSourceAs<GeoJsonSource>(SRC_POINTS)?.setGeoJson(FeatureCollection.fromFeatures(feats))
        }
        if (force || prev?.avoidZones !== overlay.avoidZones) {
            val feats = overlay.avoidZones.map { z ->
                val ring = z.ring.map { Point.fromLngLat(it.lon, it.lat) } + Point.fromLngLat(z.ring.first().lon, z.ring.first().lat)
                Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)), JsonObject().apply { addProperty("id", z.id) })
            }
            st.getSourceAs<GeoJsonSource>(SRC_ZONES)?.setGeoJson(FeatureCollection.fromFeatures(feats))
        }
        if (force || prev?.position != overlay.position || prev?.snapped != overlay.snapped) {
            val p = overlay.snapped ?: overlay.position?.point
            val feats = if (p == null) emptyList() else {
                val accM = overlay.position?.accuracyMeters?.toDouble() ?: 0.0
                val px = if (accM > 0) (accM / map.projection.getMetersPerPixelAtLatitude(p.lat)).toFloat().coerceIn(0f, 400f) else 0f
                listOf(Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat), JsonObject().apply { addProperty("accuracyPx", px) }))
            }
            st.getSourceAs<GeoJsonSource>(SRC_POSITION)?.setGeoJson(FeatureCollection.fromFeatures(feats))
        }
    }

    fun setDragPreview(p: LatLng?) {
        val st = style ?: return
        val feats = if (p == null) emptyList() else listOf(Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)))
        st.getSourceAs<GeoJsonSource>(SRC_DRAG)?.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    // ---- Camera ------------------------------------------------------------------------------------------------

    fun fitRoutes(overlay: MapOverlay, paddingPx: Int, animate: Boolean = true) {
        val pts = overlay.routes.flatMap { it.geometry } + overlay.waypoints.map { it.point } + listOfNotNull(overlay.position?.point)
        if (pts.size < 2) { pts.firstOrNull()?.let { centerOn(it, 14.0, animate) }; return }
        val b = GeoMath.run { dev.hansel.model.BoundingBox.of(pts) }
        val bounds = LatLngBounds.from(b.north, b.east, b.south, b.west)
        val update = CameraUpdateFactory.newLatLngBounds(bounds, paddingPx)
        if (animate) map.easeCamera(update, 600) else map.moveCamera(update)
    }

    fun centerOn(p: LatLng, zoom: Double = 15.0, animate: Boolean = true) {
        val update = CameraUpdateFactory.newLatLngZoom(MlLatLng(p.lat, p.lon), zoom)
        if (animate) map.easeCamera(update, 500) else map.moveCamera(update)
    }

    /** Heading-up follow camera, with the puck placed in the lower third of the screen via padding. */
    fun follow(p: LatLng, bearing: Double?, zoom: Double = 16.5, tilt: Double = 45.0, bottomOffsetPx: Int = 0) {
        val pos = CameraPosition.Builder()
            .target(MlLatLng(p.lat, p.lon))
            .zoom(zoom)
            .tilt(tilt)
            .bearing(bearing ?: map.cameraPosition.bearing)
            .padding(0.0, bottomOffsetPx.toDouble(), 0.0, 0.0)
            .build()
        map.easeCamera(CameraUpdateFactory.newCameraPosition(pos), 900, false)
    }

    companion object {
        const val SRC_ROUTES = "hansel-routes"
        const val SRC_POINTS = "hansel-points"
        const val SRC_ZONES = "hansel-zones"
        const val SRC_POSITION = "hansel-position"
        const val SRC_DRAG = "hansel-drag"
        const val L_ROUTES_ALT = "hansel-routes-alt"
        const val L_ROUTE_CASING = "hansel-route-casing"
        const val L_ROUTE = "hansel-route"
        const val L_POINTS = "hansel-points"
        const val L_ZONES_FILL = "hansel-zones-fill"
        const val L_ZONES_LINE = "hansel-zones-line"
        const val L_POSITION_HALO = "hansel-position-halo"
        const val L_POSITION = "hansel-position"
        const val L_DRAG = "hansel-drag"
        val ROUTE_LAYERS = arrayOf(L_ROUTE, L_ROUTE_CASING, L_ROUTES_ALT)
    }
}
