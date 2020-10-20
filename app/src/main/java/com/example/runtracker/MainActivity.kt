package com.example.runtracker

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StrictMode
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.runtracker.databinding.ActivityMainBinding
import com.mapbox.android.core.location.*
import com.mapbox.android.core.permissions.PermissionsListener
import com.mapbox.android.core.permissions.PermissionsManager
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.geometry.LatLngBounds
import com.mapbox.mapboxsdk.location.LocationComponentActivationOptions
import com.mapbox.mapboxsdk.location.LocationComponentOptions
import com.mapbox.mapboxsdk.location.modes.CameraMode
import com.mapbox.mapboxsdk.location.modes.RenderMode
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.snapshotter.MapSnapshotter
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.Property
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import com.mapbox.turf.TurfMeasurement
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference


class MainActivity : AppCompatActivity(), PermissionsListener {

    companion object {
        private const val ROUTE_SOURCE = "ROUTE_SOURCE"
        private const val ROUTE_LAYER = "ROUTE_LAYER"
    }

    private var mapView: MapView? = null
    private var locationEngine: LocationEngine? = null
    private var permissionsManager: PermissionsManager? = null
    private var map: MapboxMap? = null
    private var mapSnapShotter: MapSnapshotter? = null
    private val DEFAULT_INTERVAL_IN_MILLISECONDS = 1000L
    private val DEFAULT_MAX_WAIT_TIME = DEFAULT_INTERVAL_IN_MILLISECONDS * 5
    private var activityWeakReference: WeakReference<MainActivity>? = null
    private val routeCoordinates: MutableList<Point> = mutableListOf()
    private var tracking: Boolean = false
    private var fabColor: Int = R.color.green
    private var distance: Double = 0.0
    private var distanceBetweenLastAndSecondToLastClickPoint: Double = 0.0
    private var hasStartedSnapshotGeneration: Boolean? = null

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mapbox.getInstance(this, getString(R.string.mapbox_access_token))
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.startStopFab.apply {
            setOnClickListener{
                toggleTracking()
            }
            backgroundTintList = ColorStateList.valueOf(getColor(fabColor))
        }
        binding.travelDistance.text = distance.toString()
        binding.measureUnitToggle.apply {
            check(R.id.kilometers)
        }
        hasStartedSnapshotGeneration = false;
        mapView = binding.mapView.apply {
            onCreate(savedInstanceState)
            getMapAsync { mapBoxMap ->
                map = mapBoxMap
                onMapReady()
                // To account for new security measures regarding file management that were released with Android Nougat.

                // To account for new security measures regarding file management that were released with Android Nougat.
                val builder = StrictMode.VmPolicy.Builder()
                StrictMode.setVmPolicy(builder.build())
            }
        }
    }

    fun screenShot(latLngBounds: LatLngBounds, height: Int, width: Int) {
        map?.getStyle { style ->
            if (mapSnapShotter == null) {
                // Initialize snapshotter with map dimensions and given bounds
                val options = MapSnapshotter.Options(width, height)
                    .withRegion(latLngBounds)
                    .withCameraPosition(map?.cameraPosition)
                    .withStyleBuilder(Style.Builder().fromUri(style.uri))
                mapSnapShotter = MapSnapshotter(this, options)
            } else {
                // Reuse pre-existing MapSnapshotter instance
                mapSnapShotter?.setSize(width, height)
                mapSnapShotter?.setRegion(latLngBounds)
                mapSnapShotter?.setCameraPosition(map?.cameraPosition)
            }
            mapSnapShotter?.start { snapshot ->
                val bitmapOfMapSnapshotImage = snapshot.bitmap
                val bmpUri: Uri = getLocalBitmapUri(bitmapOfMapSnapshotImage)!!
                val shareIntent = Intent()
                shareIntent.putExtra(Intent.EXTRA_STREAM, bmpUri)
                shareIntent.type = "image/png"
                shareIntent.action = Intent.ACTION_SEND
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(shareIntent, "Share map image"))
                hasStartedSnapshotGeneration = false
            }
        }
    }

    private fun getLocalBitmapUri(bmp: Bitmap): Uri? {
        var bmpUri: Uri? = null
        val file = File(
            getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "share_image_" + System.currentTimeMillis() + ".png"
        )
        var out: FileOutputStream? = null
        try {
            out = FileOutputStream(file)
            bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
            try {
                out.close()
            } catch (exception: IOException) {
                exception.printStackTrace()
            }
            bmpUri = Uri.fromFile(file)
        } catch (exception: FileNotFoundException) {
            exception.printStackTrace()
        }
        return bmpUri
    }

    fun onMapReady() {
        map?.setStyle(Style.MAPBOX_STREETS) { style ->

            style.addSource(GeoJsonSource(ROUTE_SOURCE))
            style.addLayer(
                LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineColor(Color.BLUE)
                )
            )
            binding.cameraFab.setOnClickListener {
                if (!hasStartedSnapshotGeneration!!) {
                    hasStartedSnapshotGeneration = true;
                    map?.projection?.visibleRegion?.latLngBounds?.let { it1 ->
                        mapView?.measuredHeight?.let { it2 ->
                            screenShot(
                                it1,
                                it2,
                                mapView?.measuredWidth!!
                            )
                        }
                    }
                }
            }
            enableLocationComponent(style)
        }
    }

    private fun toggleTracking() {
        tracking = !tracking

        if(fabColor == R.color.green) fabColor = R.color.red
        else fabColor = R.color.green

        binding.startStopFab.setBackgroundTintList(ColorStateList.valueOf(getColor(fabColor)))
    }

    private fun editRoute(currentLocation: Point) {
        if(tracking) {
            routeCoordinates?.add(currentLocation)
            val size = routeCoordinates.size
            if (size >= 2) {
                distanceBetweenLastAndSecondToLastClickPoint = TurfMeasurement.distance(
                    routeCoordinates[size - 2], routeCoordinates[size - 1]
                )
                distance += distanceBetweenLastAndSecondToLastClickPoint

                val checked = binding.measureUnitToggle.checkedButtonId
                var finalDistance = 0.0
                if(checked.equals(R.id.kilometers)) {
                    finalDistance = distance
                } else {
                    finalDistance = distance/1.609344
                }
                binding.travelDistance.text = "%.2f".format(finalDistance)
            }
        } else {
            routeCoordinates.clear()
            distance = 0.0
            binding.travelDistance.text = "0.0"
        }

        map?.getStyle { style ->
            val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)
            source?.setGeoJson(
                FeatureCollection.fromFeatures(
                    arrayOf(
                        Feature.fromGeometry(
                            LineString.fromLngLats(routeCoordinates)
                        )
                    )
                )
            )
            }
    }

    @SuppressLint("MissingPermission")
    fun enableLocationComponent(loadedMapStyle: Style) {

        if (PermissionsManager.areLocationPermissionsGranted(this)) {

            val locationComponentOptions = LocationComponentOptions.builder(this)
                    .pulseEnabled(true)
                    .build()

            val locationComponentActivationOptions = LocationComponentActivationOptions.builder(
                this,
                loadedMapStyle
            )
                    .locationComponentOptions(locationComponentOptions)
                    .useDefaultLocationEngine(false)
                    .build()

            map?.locationComponent?.apply {
                activateLocationComponent(locationComponentActivationOptions)
                isLocationComponentEnabled = true
                setCameraMode(CameraMode.TRACKING, 2000L, 12.0, null, null, null)
                zoomWhileTracking(12.0)
                tiltWhileTracking(12.00)
                renderMode = RenderMode.COMPASS
            }

            initLocationEngine()
        } else {
            permissionsManager = PermissionsManager(this)
            permissionsManager?.requestLocationPermissions(this@MainActivity)
        }
    }

    @SuppressLint("MissingPermission")
    private fun initLocationEngine() {
        activityWeakReference = WeakReference(this@MainActivity)
        locationEngine = LocationEngineProvider.getBestLocationEngine(this)
        val request = LocationEngineRequest.Builder(DEFAULT_INTERVAL_IN_MILLISECONDS)
            .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
            .setMaxWaitTime(DEFAULT_MAX_WAIT_TIME).build()

        locationEngine?.requestLocationUpdates(
            request,
            object : LocationEngineCallback<LocationEngineResult> {

                override fun onSuccess(result: LocationEngineResult) {
                    val activity: MainActivity? = activityWeakReference?.get()
                    if (activity != null) {
                        val location = result.lastLocation ?: return

                        if (activity.map != null && result.lastLocation != null) {

                            activity.map?.locationComponent?.apply {
                                forceLocationUpdate(result.lastLocation)
                                editRoute(
                                    Point.fromLngLat(
                                        result.lastLocation!!.longitude,
                                        result.lastLocation!!.latitude
                                    )
                                )
                            }
                        }
                    }
                }

                override fun onFailure(exception: Exception) {
                    Log.d("LocationChangeActivity", exception.localizedMessage)
                }
            },
            mainLooper
        )

        locationEngine?.getLastLocation(object : LocationEngineCallback<LocationEngineResult> {
            override fun onSuccess(result: LocationEngineResult) {
                val activity: MainActivity? = activityWeakReference?.get()
                if (activity != null) {
                    val location = result.lastLocation ?: return

                    if (activity.map != null && result.lastLocation != null) {
                        activity.map?.locationComponent?.apply {
                            forceLocationUpdate(result.lastLocation)
                            editRoute(
                                Point.fromLngLat(
                                    result.lastLocation!!.longitude,
                                    result.lastLocation!!.latitude
                                )
                            )
                        }
                    }
                }
            }

            override fun onFailure(exception: Exception) {
                Log.d("LocationChangeActivity", exception.localizedMessage)
            }
        })
    }

    override fun onExplanationNeeded(permissionsToExplain: List<String?>?) {
        Toast.makeText(
            this,
            getString(R.string.user_location_permission_explanation),
            Toast.LENGTH_LONG
        )
            .show()
    }

    override fun onPermissionResult(granted: Boolean) {

    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if(PermissionsManager.areLocationPermissionsGranted(this)) {
            map?.getStyle { it ->
                enableLocationComponent(it)
            }
        } else {
            Toast.makeText(
                this,
                getString(R.string.user_location_permission_not_granted),
                Toast.LENGTH_LONG
            )
                    .show()
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView?.onPause()
        mapSnapShotter?.cancel()
    }

    override fun onStop() {
        super.onStop()
        mapView?.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Prevent leaks
        locationEngine?.removeLocationUpdates(object :
            LocationEngineCallback<LocationEngineResult> {
            override fun onSuccess(result: LocationEngineResult?) {
                TODO("Not yet implemented")
            }

            override fun onFailure(exception: Exception) {
                TODO("Not yet implemented")
            }
        })
        mapView?.onDestroy();
    }
}