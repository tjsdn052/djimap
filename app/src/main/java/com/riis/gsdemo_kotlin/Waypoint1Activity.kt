package com.riis.gsdemo_kotlin

// Android
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

// Mapbox
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.annotations.IconFactory
import com.mapbox.mapboxsdk.annotations.Marker
import com.mapbox.mapboxsdk.annotations.MarkerOptions
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.maps.SupportMapFragment

// DJI
import dji.common.camera.SettingsDefinitions.CameraMode
import dji.common.camera.SettingsDefinitions.ShootPhotoMode
import dji.common.error.DJIError
import dji.common.flightcontroller.simulator.InitializationData
import dji.common.mission.waypoint.*
import dji.common.model.LocationCoordinate2D
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener
import dji.sdk.sdkmanager.DJISDKManager

// KotlinX Coroutines
import kotlinx.coroutines.*

// Java IO & Net
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// JSON
import org.json.JSONArray
import org.json.JSONObject

// OkHttp (for fetching waypoints from server)
import okhttp3.OkHttpClient
import okhttp3.Request


class Waypoint1Activity : AppCompatActivity(), MapboxMap.OnMapClickListener, OnMapReadyCallback, View.OnClickListener {

    // UI Elements from File 1 (Camera & Basic Controls)
    private lateinit var videoSurface: TextureView
    private lateinit var captureBtn: Button
    private lateinit var recordBtn: ToggleButton // ToggleButton for record start/stop
    private lateinit var shootPhotoModeBtn: Button
    private lateinit var recordVideoModeBtn: Button
    private lateinit var recordingTime: TextView

    private lateinit var locate: Button
    private lateinit var add: Button
    private lateinit var clear: Button
    private lateinit var config: Button
    private lateinit var upload: Button
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var sendLocationButton: Button // Common in both

    // UI Elements from File 2 (Advanced Waypoint Features & Drone Info)
    private lateinit var getWaypointsBtn: Button
    // private lateinit var inputLat: EditText // 제거됨
    // private lateinit var inputLng: EditText // 제거됨
    // private lateinit var inputAlt: EditText // 제거됨
    // private lateinit var addWaypointManual: Button // 제거됨
    private lateinit var droneLatTextView: TextView
    private lateinit var droneLngTextView: TextView
    private lateinit var droneAltTextView: TextView

    // DJI SDK related
    private var codecManager: DJICodecManager? = null
    private var receivedVideoDataListener: VideoFeeder.VideoDataListener? = null
    private var mavicMiniMissionOperator: MavicMiniMissionOperator? = null // Custom operator

    // Map and Waypoint related
    private var mapboxMap: MapboxMap? = null
    private val markers: MutableMap<Int, Marker> = ConcurrentHashMap<Int, Marker>() // Key: waypoint index
    private val waypointList = mutableListOf<Waypoint>()
    private var waypointMissionBuilder: WaypointMission.Builder? = null

    // Drone State
    private var droneLocationLat: Double = 15.0 // Initial default, will be updated by simulator/FC
    private var droneLocationLng: Double = 15.0 // Initial default
    private var droneLocationAlt: Float = 0.0f
    private var droneMarker: Marker? = null
    private var isAdd = false // Toggles waypoint adding mode

    // Mission Settings
    private var altitude = 100f // Default altitude for new waypoints
    private var speed = 10f     // Default speed
    private var finishedAction = WaypointMissionFinishedAction.NO_ACTION
    private var headingMode = WaypointMissionHeadingMode.AUTO

    companion object {
        const val TAG = "Waypoint1Activity"
        // Simulator coordinates from File 1
        private const val SIMULATED_DRONE_LAT = 42.557965
        private const val SIMULATED_DRONE_LONG = -83.154303

        fun checkGpsCoordination(latitude: Double, longitude: Double): Boolean {
            return latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 && latitude != 0.0 && longitude != 0.0
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mapbox.getInstance(this, getString(R.string.mapbox_access_token))
        setContentView(R.layout.activity_waypoint1)

        initUi()

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.onCreate(savedInstanceState)
        mapFragment.getMapAsync(this)

        addListener()
    }

    override fun onMapReady(mapboxMap: MapboxMap) {
        this.mapboxMap = mapboxMap.apply {
            addOnMapClickListener(this@Waypoint1Activity)
            setOnMarkerClickListener { marker ->
                onMarkerClick(marker)
                true // Consume the event
            }
            setStyle(Style.MAPBOX_STREETS) { style ->
                Log.d(TAG, "Mapbox style loaded: ${style.url}")
                // Optionally, move camera to a default location or current drone location if available
                cameraUpdate()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        initFlightController()
        initPreviewer()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeListener()
        receivedVideoDataListener?.let {
            VideoFeeder.getInstance()?.primaryVideoFeed?.removeVideoDataListener(it)
        }
        // CodecManager cleanup is handled by its surfaceTextureListener's onDestroyed callback
    }

    private fun initUi() {
        // Basic Controls
        locate = findViewById(R.id.locate)
        add = findViewById(R.id.add)
        clear = findViewById(R.id.clear)
        config = findViewById(R.id.config)
        upload = findViewById(R.id.upload)
        start = findViewById(R.id.start)
        stop = findViewById(R.id.stop)
        sendLocationButton = findViewById(R.id.send_location_button)

        // Camera UI
        videoSurface = findViewById(R.id.video_previewer_surface)
        videoSurface.surfaceTextureListener = surfaceTextureListener
        captureBtn = findViewById(R.id.btn_capture)
        recordBtn = findViewById(R.id.btn_record)
        shootPhotoModeBtn = findViewById(R.id.btn_shoot_photo_mode)
        recordVideoModeBtn = findViewById(R.id.btn_record_video_mode)
        recordingTime = findViewById(R.id.timer)

        // Advanced Waypoint & Drone Info UI
        getWaypointsBtn = findViewById(R.id.getWaypointsBtn)
        // inputLat = findViewById(R.id.inputLat) // 제거됨
        // inputLng = findViewById(R.id.inputLng) // 제거됨
        // inputAlt = findViewById(R.id.inputAlt) // 제거됨
        // addWaypointManual = findViewById(R.id.addWaypointManual) // 제거됨
        droneLatTextView = findViewById(R.id.droneLatTextView)
        droneLngTextView = findViewById(R.id.droneLngTextView)
        droneAltTextView = findViewById(R.id.droneAltTextView)

        // Set Click Listeners
        locate.setOnClickListener(this)
        add.setOnClickListener(this)
        clear.setOnClickListener(this)
        config.setOnClickListener(this)
        upload.setOnClickListener(this)
        start.setOnClickListener(this)
        stop.setOnClickListener(this)
        sendLocationButton.setOnClickListener(this)
        captureBtn.setOnClickListener(this)
        shootPhotoModeBtn.setOnClickListener(this)
        recordVideoModeBtn.setOnClickListener(this)
        getWaypointsBtn.setOnClickListener(this)
        // addWaypointManual.setOnClickListener(this) // 제거됨

        recordBtn.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startRecord() else stopRecord()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initFlightController() {
        DJIDemoApplication.getFlightController()?.let { flightController ->
            val simulateLocation = LocationCoordinate2D(SIMULATED_DRONE_LAT, SIMULATED_DRONE_LONG)
            flightController.simulator.start(
                InitializationData.createInstance(simulateLocation, 10, 10)
            ) { error ->
                if (error != null) {
                    Log.e(TAG, "Error starting simulator: ${error.description}")
                    setResultToToast("Simulator start failed: ${error.description}")
                } else {
                    Log.d(TAG, "Simulator started successfully at $SIMULATED_DRONE_LAT, $SIMULATED_DRONE_LONG")
                    // setResultToToast("Simulator started")
                }
            }

            flightController.setStateCallback { flightControllerState ->
                droneLocationLat = flightControllerState.aircraftLocation.latitude
                droneLocationLng = flightControllerState.aircraftLocation.longitude
                droneLocationAlt = flightControllerState.aircraftLocation.altitude

                runOnUiThread {
                    mavicMiniMissionOperator?.droneLocationMutableLiveData?.postValue(flightControllerState.aircraftLocation)
                    updateDroneLocation()
                }
            }
        } ?: run {
            Log.e(TAG, "Flight Controller not available.")
            setResultToToast("Flight Controller not available")
            runOnUiThread {
                droneLatTextView.text = "Latitude: FC N/A"
                droneLngTextView.text = "Longitude: FC N/A"
                droneAltTextView.text = "Altitude: FC N/A"
            }
        }
    }

    private fun initPreviewer() {
        val product = DJISDKManager.getInstance().product ?: return
        if (product.camera == null) {
            setResultToToast("Camera not available on product.")
            Log.e(TAG, "Camera not available")
            return
        }

        receivedVideoDataListener = VideoFeeder.VideoDataListener { videoBuffer, size ->
            codecManager?.sendDataToDecoder(videoBuffer, size)
        }

        if (VideoFeeder.getInstance()?.primaryVideoFeed != null) {
            VideoFeeder.getInstance().primaryVideoFeed.addVideoDataListener(receivedVideoDataListener)
        } else {
            setResultToToast("Primary video feed not available.")
            Log.e(TAG, "Primary video feed not available")
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            Log.d(TAG, "Surface Texture Available: $width x $height")
            codecManager = DJICodecManager(this@Waypoint1Activity, surface, width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            Log.d(TAG, "Surface Texture Size Changed: $width x $height")
            // CodecManager handles this internally if needed, or you can re-init.
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            Log.d(TAG, "Surface Texture Destroyed")
            codecManager?.cleanSurface()
            codecManager = null
            return true
        }
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.locate -> {
                updateDroneLocation()
                cameraUpdate()
            }
            R.id.add -> enableDisableAdd()
            R.id.clear -> {
                runOnUiThread {
                    mapboxMap?.clear() // Clears all visual markers from the map
                    markers.clear()    // Clear our tracked markers map
                }
                waypointList.clear() // Clear the actual waypoint data
                waypointMissionBuilder?.waypointList(waypointList)?.waypointCount(waypointList.size) // Update builder
                setResultToToast("Waypoints cleared")
            }
            R.id.config -> showSettingsDialog()
            R.id.upload -> uploadWaypointMission()
            R.id.start -> startWaypointMission()
            R.id.stop -> stopWaypointMission()
            R.id.send_location_button -> sendCurrentLocationToApi()
            R.id.btn_capture -> captureAction()
            R.id.btn_shoot_photo_mode -> switchCameraMode(CameraMode.SHOOT_PHOTO)
            R.id.btn_record_video_mode -> switchCameraMode(CameraMode.RECORD_VIDEO)
            R.id.getWaypointsBtn -> fetchWaypointsFromServer()
            // R.id.addWaypointManual -> addWaypointManually() // 제거됨
            else -> {}
        }
    }

    override fun onMapClick(point: LatLng): Boolean {
        if (isAdd) {
            val waypointIndex = waypointList.size // New waypoint will be at the end of the list
            markWaypoint(point, altitude.toDouble(), waypointIndex, null) // Heading is null for map clicks

            val waypoint = Waypoint(point.latitude, point.longitude, altitude)
            // waypoint.heading will be 0 by default. If USING_WAYPOINT_HEADING, it should be set.

            if (waypointMissionBuilder == null) {
                waypointMissionBuilder = WaypointMission.Builder().also { builder ->
                    waypointList.add(waypoint)
                    builder.waypointList(waypointList).waypointCount(waypointList.size)
                }
            } else {
                waypointMissionBuilder?.let { builder ->
                    waypointList.add(waypoint)
                    builder.waypointList(waypointList).waypointCount(waypointList.size)
                }
            }
            setResultToToast("Waypoint ${waypointIndex + 1} added at ${point.latitude}, ${point.longitude}")
        } else {
            setResultToToast("Press 'Add' to enable waypoint placement.")
        }
        return true
    }

    private fun onMarkerClick(marker: Marker) {
        if (marker == droneMarker) {
            setResultToToast("This is the current drone location.")
            return
        }

        val waypointIndex = findWaypointIndexByMarker(marker)
        if (waypointIndex != -1) {
            showDeleteWaypointDialog(waypointIndex, marker)
        } else {
            setResultToToast("Could not find waypoint for this marker.")
        }
    }

    private fun findWaypointIndexByMarker(marker: Marker): Int {
        for ((index, storedMarker) in markers) {
            if (storedMarker == marker) {
                return index
            }
        }
        return -1
    }

    private fun showDeleteWaypointDialog(waypointIndex: Int, marker: Marker) {
        if (waypointIndex < 0 || waypointIndex >= waypointList.size) {
            setResultToToast("Invalid waypoint index for deletion.")
            return
        }

        val waypoint = waypointList[waypointIndex]
        val message = String.format(
            Locale.US,
            "Delete Waypoint %d?\n\nLat: %.6f\nLng: %.6f\nAlt: %.2fm",
            waypointIndex + 1,
            waypoint.coordinate.latitude,
            waypoint.coordinate.longitude,
            waypoint.altitude
        )

        AlertDialog.Builder(this)
            .setTitle("Confirm Deletion")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ ->
                deleteWaypoint(waypointIndex, marker)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteWaypoint(waypointIndex: Int, marker: Marker) {
        try {
            waypointList.removeAt(waypointIndex)
            marker.remove() // Remove from map
            markers.remove(waypointIndex) // Remove from our tracking map

            // Re-index subsequent markers and update their titles
            reindexMarkers(waypointIndex)

            waypointMissionBuilder?.waypointList(waypointList)?.waypointCount(waypointList.size)
            setResultToToast("Waypoint ${waypointIndex + 1} deleted.")
            Log.d(TAG, "Deleted waypoint $waypointIndex. Remaining: ${waypointList.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting waypoint", e)
            setResultToToast("Error deleting waypoint: ${e.message}")
        }
    }

    private fun reindexMarkers(deletedIndex: Int) {
        val updatedMarkers = ConcurrentHashMap<Int, Marker>()
        var currentMapMarker: Marker?

        // Shift markers with index > deletedIndex
        for (i in deletedIndex until waypointList.size) {
            currentMapMarker = markers[i + 1] // Get the marker that was at the original (i+1) position
            currentMapMarker?.let {
                // Update its stored index
                updatedMarkers[i] = it
                // Update its visual title on the map
                val wp = waypointList[i]
                it.title = String.format(Locale.US, "WP %d: Lat: %.6f, Lng: %.6f", i + 1, wp.coordinate.latitude, wp.coordinate.longitude)
                it.snippet = String.format(Locale.US, "Alt: %.2fm%s", wp.altitude, if (wp.heading != 0) ", Hdg: ${wp.heading}°" else "")
            }
        }
        markers.keys.removeIf { it >= deletedIndex } // Remove old entries for deleted and shifted items
        markers.putAll(updatedMarkers) // Add the re-indexed items
    }


    private fun markWaypoint(point: LatLng, alt: Double, index: Int, heading: Int?) {
        val title = String.format(Locale.US, "WP %d: Lat: %.6f, Lng: %.6f", index + 1, point.latitude, point.longitude)
        val snippet: String = if (heading != null && heading != 0) { // DJI heading 0 is North
            String.format(Locale.US, "Alt: %.2fm, Hdg: %d°", alt, heading)
        } else {
            String.format(Locale.US, "Alt: %.2fm", alt)
        }

        val markerOptions = MarkerOptions().position(point).title(title).snippet(snippet)
        mapboxMap?.let { map ->
            markers[index]?.remove() // Remove old marker at this index if it exists
            val newMarker = map.addMarker(markerOptions)
            markers[index] = newMarker
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateDroneLocation() {
        runOnUiThread {
            droneLatTextView.text = String.format(Locale.US, "Lat: %.6f", droneLocationLat)
            droneLngTextView.text = String.format(Locale.US, "Lng: %.6f", droneLocationLng)
            droneAltTextView.text = String.format(Locale.US, "Alt: %.2f m", droneLocationAlt)
        }

        if (droneLocationLat.isNaN() || droneLocationLng.isNaN()) return

        val pos = LatLng(droneLocationLat, droneLocationLng)
        val icon = IconFactory.getInstance(this@Waypoint1Activity).fromResource(R.drawable.aircraft)
        val markerOptions = MarkerOptions().position(pos).icon(icon)

        runOnUiThread {
            droneMarker?.remove()
            if (checkGpsCoordination(droneLocationLat, droneLocationLng)) {
                droneMarker = mapboxMap?.addMarker(markerOptions)
            }
        }
    }

    private fun cameraUpdate() {
        if (droneLocationLat.isNaN() || droneLocationLng.isNaN() || mapboxMap == null) return
        val pos = LatLng(droneLocationLat, droneLocationLng)
        val zoomLevel = 18.0
        val cameraUpdate = CameraUpdateFactory.newLatLngZoom(pos, zoomLevel)
        mapboxMap?.animateCamera(cameraUpdate)
    }

    // --- Camera Actions ---
    private fun captureAction() {
        val camera = DJISDKManager.getInstance().product?.camera ?: run {
            setResultToToast("Camera not available")
            return
        }
        camera.setShootPhotoMode(ShootPhotoMode.SINGLE) { error ->
            if (error == null) {
                camera.startShootPhoto { err ->
                    if (err == null) {
                        setResultToToast("Photo captured successfully")
                    } else {
                        setResultToToast("Photo capture failed: ${err.description}")
                    }
                }
            } else {
                setResultToToast("Failed to set photo mode: ${error.description}")
            }
        }
    }

    private fun startRecord() {
        val camera = DJISDKManager.getInstance().product?.camera ?: run {
            setResultToToast("Camera not available")
            recordBtn.isChecked = false
            return
        }
        camera.startRecordVideo { error ->
            if (error == null) {
                setResultToToast("Started recording")
                // Handle recording time UI if needed
            } else {
                setResultToToast("Recording start failed: ${error.description}")
                recordBtn.isChecked = false
            }
        }
    }

    private fun stopRecord() {
        val camera = DJISDKManager.getInstance().product?.camera ?: run {
            setResultToToast("Camera not available")
            return
        }
        camera.stopRecordVideo { error ->
            if (error == null) {
                setResultToToast("Stopped recording")
            } else {
                setResultToToast("Recording stop failed: ${error.description}")
            }
        }
    }

    private fun switchCameraMode(mode: CameraMode) {
        val camera = DJISDKManager.getInstance().product?.camera ?: run {
            setResultToToast("Camera not available")
            return
        }
        camera.setMode(mode) { error ->
            if (error == null) {
                setResultToToast("Switched to ${mode.name}")
            } else {
                setResultToToast("Switch camera mode failed: ${error.description}")
            }
        }
    }

    // --- Waypoint Mission Actions ---
    /* // 제거됨: addWaypointManually 함수
    private fun addWaypointManually() {
        val latStr = inputLat.text.toString()
        val lngStr = inputLng.text.toString()
        val altStr = inputAlt.text.toString()

        if (latStr.isBlank() || lngStr.isBlank() || altStr.isBlank()) {
            setResultToToast("Enter Lat, Lng, and Alt for waypoint.")
            return
        }

        try {
            val latitude = latStr.toDouble()
            val longitude = lngStr.toDouble()
            val altitudeInput = altStr.toFloat()

            if (!checkGpsCoordination(latitude, longitude)) {
                setResultToToast("Invalid GPS coordinates.")
                return
            }

            val newPoint = LatLng(latitude, longitude)
            val waypointIndex = waypointList.size
            markWaypoint(newPoint, altitudeInput.toDouble(), waypointIndex, null)

            val waypoint = Waypoint(latitude, longitude, altitudeInput)

            if (waypointMissionBuilder == null) {
                waypointMissionBuilder = WaypointMission.Builder().also { builder ->
                    waypointList.add(waypoint)
                    builder.waypointList(waypointList).waypointCount(waypointList.size)
                }
            } else {
                waypointList.add(waypoint)
                waypointMissionBuilder?.waypointList(waypointList)?.waypointCount(waypointList.size)
            }
            setResultToToast("Waypoint ${waypointIndex + 1} added manually.")
            inputLat.text.clear()
            inputLng.text.clear()
            inputAlt.text.clear()

        } catch (e: NumberFormatException) {
            setResultToToast("Invalid number format for coordinates or altitude.")
            Log.e(TAG, "NumberFormatException: ${e.message}")
        }
    }
    */

    private fun fetchWaypointsFromServer() {
        val buildingId = "26" // Example buildingId, make this dynamic if needed
        if (buildingId.isBlank()) {
            setResultToToast("Building ID is required.")
            return
        }

        setResultToToast("Fetching waypoints for building ID: $buildingId...")
        val serverUrl = "http://3.37.127.247:8080/waypoints$buildingId"
        Log.d(TAG, "Fetching from URL: $serverUrl")


        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(serverUrl).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body()?.string()
                    if (responseBody != null) {
                        Log.d(TAG, "Server response: $responseBody")
                        val fetchedWaypoints = mutableListOf<Waypoint>()
                        val fetchedMarkerData = mutableListOf<Triple<LatLng, Double, Int>>() // LatLng, Alt, Heading

                        val jsonArray = JSONArray(responseBody)
                        for (i in 0 until jsonArray.length()) {
                            val wpObject = jsonArray.getJSONObject(i)
                            val lat = wpObject.optDouble("lat")
                            val lng = wpObject.optDouble("lng")
                            val altF = wpObject.optDouble("altitude").toFloat()
                            val heading = wpObject.optInt("heading", 0)

                            if (checkGpsCoordination(lat, lng)) {
                                val waypoint = Waypoint(lat, lng, altF)
                                waypoint.heading = heading
                                fetchedWaypoints.add(waypoint)
                                fetchedMarkerData.add(Triple(LatLng(lat, lng), altF.toDouble(), heading))
                            }
                        }

                        withContext(Dispatchers.Main) {
                            mapboxMap?.clear()
                            markers.clear()
                            waypointList.clear()

                            waypointList.addAll(fetchedWaypoints)
                            if (waypointMissionBuilder == null) {
                                waypointMissionBuilder = WaypointMission.Builder()
                            }
                            waypointMissionBuilder?.waypointList(waypointList)?.waypointCount(waypointList.size)

                            fetchedMarkerData.forEachIndexed { index, data ->
                                markWaypoint(data.first, data.second, index, data.third)
                            }

                            if (fetchedWaypoints.isNotEmpty()) {
                                cameraUpdateToFirstWaypoint(fetchedWaypoints.first())
                                setResultToToast("Loaded ${fetchedWaypoints.size} waypoints from server.")
                                configWayPointMission()
                            } else {
                                setResultToToast("No valid waypoints received from server.")
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) { setResultToToast("Empty response from server.") }
                    }
                } else {
                    val errorBody = response.body()?.string() ?: "Unknown error"
                    Log.e(TAG, "Server error: ${response.code()} ${response.message()} - $errorBody")
                    withContext(Dispatchers.Main) {
                        setResultToToast("Failed to load waypoints: ${response.code()}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching waypoints", e)
                withContext(Dispatchers.Main) {
                    setResultToToast("Error fetching waypoints: ${e.message}")
                }
            }
        }
    }


    private fun sendCurrentLocationToApi() {
        if (!checkGpsCoordination(droneLocationLat, droneLocationLng)) {
            setResultToToast("Invalid GPS coordinates to send.")
            return
        }

        val jsonObject = JSONObject()
        jsonObject.put("latitude", droneLocationLat)
        jsonObject.put("longitude", droneLocationLng)
        jsonObject.put("altitude", droneLocationAlt.toDouble())

        val jsonData = jsonObject.toString()
        val apiUrl = "http://3.37.127.247:8080/waypoint"

        setResultToToast("Sending current location...")
        Log.d(TAG, "Sending data: $jsonData to $apiUrl")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(apiUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 15000
                connection.readTimeout = 10000

                OutputStreamWriter(connection.outputStream, "UTF-8").use { it.write(jsonData); it.flush() }

                val responseCode = connection.responseCode
                val responseBodyStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream ?: connection.inputStream
                val responseBody = responseBodyStream.bufferedReader(Charsets.UTF_8).use { it.readText() }


                Log.d(TAG, "API Response Code: $responseCode, Body: $responseBody")
                withContext(Dispatchers.Main) {
                    if (responseCode in 200..299) {
                        setResultToToast("Location sent! Server: ${responseBody.take(100)}")
                    } else {
                        setResultToToast("Send location failed ($responseCode): ${responseBody.take(100)}")
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending location data", e)
                withContext(Dispatchers.Main) { setResultToToast("Error sending: ${e.message}") }
            }
        }
    }

    private fun configWayPointMission() {
        if (waypointMissionBuilder == null && waypointList.isNotEmpty()) {
            waypointMissionBuilder = WaypointMission.Builder().waypointList(waypointList).waypointCount(waypointList.size)
        } else if (waypointMissionBuilder == null) {
            waypointMissionBuilder = WaypointMission.Builder()
        }

        val builder = waypointMissionBuilder ?: return

        builder.finishedAction(finishedAction)
            .headingMode(headingMode)
            .autoFlightSpeed(speed)
            .maxFlightSpeed(speed)
            .flightPathMode(WaypointMissionFlightPathMode.NORMAL)
            .gotoFirstWaypointMode(WaypointMissionGotoWaypointMode.SAFELY)
            .isGimbalPitchRotationEnabled = true

        if (waypointList.isNotEmpty()) {
            val missionWaypoints = mutableListOf<Waypoint>()
            for (sourceWaypoint in waypointList) {
                val missionWaypoint = Waypoint(sourceWaypoint.coordinate.latitude, sourceWaypoint.coordinate.longitude, sourceWaypoint.altitude)
                missionWaypoint.heading = sourceWaypoint.heading
                missionWaypoint.actionRepeatTimes = 1
                missionWaypoint.actionTimeoutInSeconds = 30
                missionWaypoint.turnMode = WaypointTurnMode.CLOCKWISE

                missionWaypoint.waypointActions.clear()
                missionWaypoint.addAction(WaypointAction(WaypointActionType.GIMBAL_PITCH, 0))
                missionWaypoint.addAction(WaypointAction(WaypointActionType.START_TAKE_PHOTO, 0))
                missionWaypoint.addAction(WaypointAction(WaypointActionType.STAY, 5))

                missionWaypoint.shootPhotoDistanceInterval = 0f
                missionWaypoints.add(missionWaypoint)
            }
            builder.waypointList(missionWaypoints).waypointCount(missionWaypoints.size)
        } else {
            builder.waypointList(emptyList()).waypointCount(0)
        }

        getWaypointMissionOperator()?.let { operator ->
            val missionToLoad = builder.build()
            val error = operator.loadMission(missionToLoad)
            if (error == null) {
                setResultToToast("Load mission succeeded" + if (missionToLoad.waypointCount == 0) " (cleared existing)" else " (${missionToLoad.waypointCount} WPs)")
            } else {
                setResultToToast("Load mission failed: ${error.description} (Code: ${error.errorCode})")
            }
        }
    }

    private fun uploadWaypointMission() {
        if (waypointMissionBuilder == null || waypointList.isEmpty()) {
            setResultToToast("No waypoints to upload.")
            return
        }
        configWayPointMission()

        getWaypointMissionOperator()?.uploadMission { error ->
            if (error == null) {
                setResultToToast("Mission upload successful!")
            } else {
                setResultToToast("Mission upload failed: ${error.description} (Code: ${error.errorCode})")
            }
        }
    }

    private fun startWaypointMission() {
        getWaypointMissionOperator()?.startMission { error ->
            setResultToToast("Mission Start: " + if (error == null) "Successfully" else error.description)
        }
    }

    private fun stopWaypointMission() {
        getWaypointMissionOperator()?.stopMission { error ->
            setResultToToast("Mission Stop: " + if (error == null) "Successfully" else error.description)
        }
    }


    private fun showSettingsDialog() {
        val settingsView = layoutInflater.inflate(R.layout.dialog_waypointsetting, null) as LinearLayout

        val altitudeEditText = settingsView.findViewById<EditText>(R.id.altitude)
        val speedSeekBar = settingsView.findViewById<SeekBar>(R.id.speedSeekBar)
        val speedValueTextView = settingsView.findViewById<TextView>(R.id.speedValueTextView)
        val actionAfterFinishedRG = settingsView.findViewById<RadioGroup>(R.id.actionAfterFinished)
        val headingRG = settingsView.findViewById<RadioGroup>(R.id.heading)

        altitudeEditText.setText(altitude.toInt().toString())
        speedSeekBar.progress = speed.toInt()
        speedValueTextView.text = String.format(Locale.US, "%.1f m/s", speed)

        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                speed = progress.toFloat()
                speedValueTextView.text = String.format(Locale.US, "%.1f m/s", speed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        when (finishedAction) {
            WaypointMissionFinishedAction.NO_ACTION -> actionAfterFinishedRG.check(R.id.finishNone)
            WaypointMissionFinishedAction.GO_HOME -> actionAfterFinishedRG.check(R.id.finishGoHome)
            WaypointMissionFinishedAction.AUTO_LAND -> actionAfterFinishedRG.check(R.id.finishAutoLanding)
            WaypointMissionFinishedAction.GO_FIRST_WAYPOINT -> actionAfterFinishedRG.check(R.id.finishToFirst)
            else -> {}
        }
        when (headingMode) {
            WaypointMissionHeadingMode.AUTO -> headingRG.check(R.id.headingNext)
            WaypointMissionHeadingMode.USING_INITIAL_DIRECTION -> headingRG.check(R.id.headingInitDirec)
            WaypointMissionHeadingMode.CONTROL_BY_REMOTE_CONTROLLER -> headingRG.check(R.id.headingRC)
            WaypointMissionHeadingMode.USING_WAYPOINT_HEADING -> headingRG.check(R.id.headingWP)
            else -> {}
        }

        actionAfterFinishedRG.setOnCheckedChangeListener { _, checkedId ->
            finishedAction = when (checkedId) {
                R.id.finishNone -> WaypointMissionFinishedAction.NO_ACTION
                R.id.finishGoHome -> WaypointMissionFinishedAction.GO_HOME
                R.id.finishAutoLanding -> WaypointMissionFinishedAction.AUTO_LAND
                R.id.finishToFirst -> WaypointMissionFinishedAction.GO_FIRST_WAYPOINT
                else -> finishedAction
            }
        }

        headingRG.setOnCheckedChangeListener { _, checkedId ->
            headingMode = when (checkedId) {
                R.id.headingNext -> WaypointMissionHeadingMode.AUTO
                R.id.headingInitDirec -> WaypointMissionHeadingMode.USING_INITIAL_DIRECTION
                R.id.headingRC -> WaypointMissionHeadingMode.CONTROL_BY_REMOTE_CONTROLLER
                R.id.headingWP -> WaypointMissionHeadingMode.USING_WAYPOINT_HEADING
                else -> headingMode
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Waypoint Settings")
            .setView(settingsView)
            .setPositiveButton("OK") { _, _ ->
                altitude = nullToIntegerDefault(altitudeEditText.text.toString()).toFloat()
                Log.d(TAG, "Settings Updated - Altitude: $altitude, Speed: $speed, FinishAction: $finishedAction, HeadingMode: $headingMode")
                configWayPointMission()
            }
            .setNegativeButton("Cancel", null)
            .create()
            .show()
    }


    // --- Listeners & Helpers ---
    private fun addListener() {
        getWaypointMissionOperator()?.addListener(eventNotificationListener)
    }

    private fun removeListener() {
        getWaypointMissionOperator()?.removeListener()
    }

    private val eventNotificationListener: WaypointMissionOperatorListener = object : WaypointMissionOperatorListener {
        override fun onDownloadUpdate(downloadEvent: WaypointMissionDownloadEvent) {
            Log.d(TAG, "Mission Download Update: ${downloadEvent.progress}")
        }
        override fun onUploadUpdate(uploadEvent: WaypointMissionUploadEvent) {
            Log.d(TAG, "Mission Upload Update: ${uploadEvent.progress}, State: ${uploadEvent.currentState}")
            if (uploadEvent.currentState == WaypointMissionState.READY_TO_EXECUTE) {
                setResultToToast("Mission ready to execute after upload.")
            }
        }
        override fun onExecutionUpdate(executionEvent: WaypointMissionExecutionEvent) {
            Log.d(TAG, "Mission Execution Update. Progress: ${executionEvent.progress?.targetWaypointIndex}, State: ${executionEvent.currentState}")
        }
        override fun onExecutionStart() {
            setResultToToast("Mission Execution Started.")
            Log.d(TAG, "Mission Execution Started")
        }
        override fun onExecutionFinish(error: DJIError?) {
            val message = "Execution Finished: " + if (error == null) "Success!" else "${error.description} (Code: ${error.errorCode})"
            setResultToToast(message)
            Log.d(TAG, message)
        }
    }

    private fun getWaypointMissionOperator(): MavicMiniMissionOperator? {
        if (mavicMiniMissionOperator == null) {
            Log.d(TAG, "Initializing MavicMiniMissionOperator")
            mavicMiniMissionOperator = MavicMiniMissionOperator(this)
        }
        return mavicMiniMissionOperator
    }

    private fun enableDisableAdd() {
        isAdd = !isAdd
        add.text = if (isAdd) "Exit Add" else "Add WPs"
        setResultToToast(if(isAdd) "Map click will add waypoints." else "Waypoint adding disabled.")
    }

    private fun cameraUpdateToFirstWaypoint(firstWaypoint: Waypoint) {
        if (mapboxMap == null) return
        val pos = LatLng(firstWaypoint.coordinate.latitude, firstWaypoint.coordinate.longitude)
        mapboxMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 18.0))
    }

    private fun nullToIntegerDefault(value: String): String {
        val trimmedValue = value.trim()
        return if (isIntValue(trimmedValue)) trimmedValue else "0"
    }

    private fun isIntValue(value: String): Boolean {
        return value.toIntOrNull() != null
    }

    private fun setResultToToast(string: String) {
        runOnUiThread { Toast.makeText(this, string, Toast.LENGTH_SHORT).show() }
    }
}