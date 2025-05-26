package com.riis.gsdemo_kotlin

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
// import androidx.lifecycle.lifecycleScope // 현재 직접 사용 안함
import com.riis.gsdemo_kotlin.DJIDemoApplication.getCameraInstance
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.error.DJIMissionError
import dji.common.flightcontroller.LocationCoordinate3D
import dji.common.flightcontroller.virtualstick.*
import dji.common.gimbal.GimbalState
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import dji.common.mission.MissionState
import dji.common.mission.waypoint.Waypoint
import dji.common.mission.waypoint.WaypointMission
import dji.common.mission.waypoint.WaypointMissionState
import dji.common.model.LocationCoordinate2D
import dji.common.util.CommonCallbacks
import dji.sdk.camera.Camera
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener
// import dji.common.mission.waypoint.WaypointMissionDownloadEvent // 현재 직접 사용 안함
// import dji.common.mission.waypoint.WaypointMissionExecutionEvent // 현재 직접 사용 안함
// import dji.common.mission.waypoint.WaypointMissionUploadEvent // 현재 직접 사용 안함


import java.util.*
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

private const val TAG = "MMMissionOperator"

class MavicMiniMissionOperator(context: Context) {

    private var isLanding: Boolean = false
    // private var isLanded: Boolean = false // 현재 직접 사용 안함
    private var isAirborne: Boolean = false
    private var photoIsSuccess: Boolean = false
    // private var observeGimbal: Boolean = false // 현재 직접 사용 안함
    private val activity: AppCompatActivity
    private val mContext = context
    // private var gimbalObserver: Observer<Float>? = null // 짐벌 각도에 따른 이륙 로직 제거

    private var state: MissionState = WaypointMissionState.INITIAL_PHASE
    private lateinit var mission: WaypointMission
    private lateinit var waypoints: MutableList<Waypoint>
    private lateinit var currentWaypoint: Waypoint

    private var operatorListener: WaypointMissionOperatorListener? = null
    var droneLocationMutableLiveData: MutableLiveData<LocationCoordinate3D> =
        MutableLiveData()
    val droneLocationLiveData: LiveData<LocationCoordinate3D> = droneLocationMutableLiveData

    private var travelledLongitude = false
    private var travelledLatitude = false
    private var waypointTracker = 0

    private var sendDataTimer = Timer()

    private var originalLongitudeDiff = -1.0
    private var originalLatitudeDiff = -1.0
    private var directions = Direction(altitude = 0f) // Yaw 기본값 0f 유지

    // private var currentGimbalPitch: Float = 0f // 직접 사용하지 않으므로 제거 가능
    // private var gimbalPitchLiveData: MutableLiveData<Float> = MutableLiveData() // 직접 사용하지 않으므로 제거 가능

    // private var distanceToWaypoint = 0.0 // 현재 직접 사용 안함
    private var photoTakenToggle = false
    private var isHoveringForPhoto = false

    private val REACH_THRESHOLD_DEGREES = 0.0000025 // 약 0.2~0.3m
    private val HOVER_DURATION_MS = 2000L // 호버링 시간 2초

    init {
        activity = context as AppCompatActivity
        initFlightController()
        // initGimbalListener() // 짐벌 각도 실시간 추적 불필요 시 제거 가능 또는 유지
    }

    private fun initFlightController() {
        DJIDemoApplication.getFlightController()?.let { flightController ->
            flightController.setVirtualStickModeEnabled(true, null)
            flightController.rollPitchControlMode = RollPitchControlMode.VELOCITY
            flightController.yawControlMode = YawControlMode.ANGLE // 기체가 이동 방향을 보도록 하려면 ANGLE 대신 ANGULAR_VELOCITY 사용 및 yaw 제어 필요
            flightController.verticalControlMode = VerticalControlMode.POSITION
            flightController.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
        } ?: Log.e(TAG, "FlightController 초기화 실패")
    }

    // 짐벌 각도 실시간 추적이 필요 없다면 이 함수는 제거해도 됩니다.
    // 만약 특정 시점에 짐벌 각도를 확인해야 한다면 유지합니다.
    /*
    private fun initGimbalListener() {
        DJIDemoApplication.getGimbal()?.setStateCallback { gimbalState: GimbalState ->
            // currentGimbalPitch = gimbalState.attitudeInDegrees.pitch
            // gimbalPitchLiveData.postValue(currentGimbalPitch)
            // Log.d(TAG, "Current Gimbal Pitch: ${gimbalState.attitudeInDegrees.pitch}")
        } ?: Log.e(TAG, "Gimbal 초기화 실패")
    }
    */

    private fun takePhoto(): Boolean {
        val camera: Camera = getCameraInstance() ?: run {
            Log.e(TAG, "사진 촬영 실패: 카메라 인스턴스 없음")
            proceedToNextWaypointAfterAction()
            return false
        }
        this.photoIsSuccess = false
        val photoMode = SettingsDefinitions.ShootPhotoMode.SINGLE
        camera.setShootPhotoMode(photoMode) { djiError ->
            if (djiError == null) {
                camera.startShootPhoto { djiErrorSecond ->
                    if (djiErrorSecond == null) {
                        Log.d(TAG, "사진 촬영 명령 성공")
                        showToast(mContext, "사진 촬영 성공!")
                        this.photoIsSuccess = true
                    } else {
                        Log.e(TAG, "사진 촬영 시작 실패: ${djiErrorSecond.description}")
                        showToast(mContext, "사진 촬영 실패: ${djiErrorSecond.description}")
                    }
                    proceedToNextWaypointAfterAction()
                }
            } else {
                Log.e(TAG, "사진 촬영 모드 설정 실패: ${djiError.description}")
                proceedToNextWaypointAfterAction()
            }
        }
        return true
    }

    fun loadMission(mission: WaypointMission?): DJIError? {
        return if (mission == null) {
            this.state = WaypointMissionState.NOT_READY
            DJIMissionError.NULL_MISSION
        } else {
            this.mission = mission
            this.waypoints = mission.waypointList
            if (this.waypoints.isEmpty()) {
                Log.e(TAG, "미션에 웨이포인트가 없습니다.")
                this.state = WaypointMissionState.NOT_READY
                return DJIMissionError.FAILED
            }
            this.state = WaypointMissionState.READY_TO_UPLOAD
            null
        }
    }

    fun uploadMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?) {
        if (this.state == WaypointMissionState.READY_TO_UPLOAD) {
            this.state = WaypointMissionState.READY_TO_START
            callback?.onResult(null)
        } else {
            this.state = WaypointMissionState.NOT_READY
            callback?.onResult(DJIMissionError.UPLOADING_WAYPOINT)
        }
    }

    fun startMission(callback: CommonCallbacks.CompletionCallback<DJIError>?) {
        if (!::mission.isInitialized || waypoints.isEmpty()) {
            showToast(mContext, "미션이 로드되지 않았거나 웨이포인트가 없습니다.")
            callback?.onResult(DJIMissionError.FAILED)
            return
        }

        waypointTracker = 0
        // isLanded = false // 사용 안함
        isLanding = false
        photoTakenToggle = false
        isHoveringForPhoto = false
        originalLatitudeDiff = -1.0
        originalLongitudeDiff = -1.0
        travelledLatitude = false
        travelledLongitude = false
        isAirborne = false // 이륙 상태 초기화

        if (this.state == WaypointMissionState.READY_TO_START) {
            Log.d(TAG, "짐벌 정면으로 회전 및 이륙 준비")
            rotateGimbalForward { gimbalError -> // 짐벌 정면으로 회전
                if (gimbalError == null) {
                    Log.d(TAG, "짐벌 정면 회전 성공. 이륙 시작.")
                    showToast(mContext, "이륙 시작")
                    DJIDemoApplication.getFlightController()?.startTakeoff { takeoffError ->
                        if (takeoffError == null) {
                            isAirborne = true // 이륙 성공 상태 업데이트
                            callback?.onResult(null)
                            this.state = WaypointMissionState.READY_TO_EXECUTE

                            getCameraInstance()?.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO) { cameraModeError ->
                                if (cameraModeError == null) {
                                    showToast(mContext, "카메라 사진 모드 전환 성공")
                                } else {
                                    showToast(mContext, "카메라 모드 전환 실패: ${cameraModeError.description}")
                                }
                            }

                            Handler(Looper.getMainLooper()).postDelayed({
                                if (this.state == WaypointMissionState.READY_TO_EXECUTE) {
                                    Log.d(TAG, "미션 실행 시작")
                                    operatorListener?.onExecutionStart()
                                    executeNextWaypointMission()
                                }
                            }, 8000) // 이륙 후 안정화 시간
                        } else {
                            showToast(mContext, "이륙 실패: ${takeoffError.description}")
                            callback?.onResult(takeoffError)
                            isAirborne = false // 이륙 실패 상태 업데이트
                        }
                    }
                } else {
                    showToast(mContext, "짐벌 회전 실패: ${gimbalError.description}. 미션 시작 취소.")
                    callback?.onResult(gimbalError)
                }
            }
        } else {
            showToast(mContext, "미션 시작 준비 안됨. 현재 상태: ${this.state}")
            callback?.onResult(DJIMissionError.FAILED)
        }
    }

    private fun rotateGimbalForward(callback: CommonCallbacks.CompletionCallback<DJIError>?) {
        // 짐벌을 정면(0도)으로 회전시킵니다.
        val rotation = Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).pitch(0f).build()
        DJIDemoApplication.getGimbal()?.rotate(rotation) { djiError ->
            if (djiError == null) {
                Log.d(TAG, "짐벌 정면으로 회전 성공")
            } else {
                Log.e(TAG, "짐벌 정면 회전 오류: ${djiError.description}")
            }
            callback?.onResult(djiError)
        }
    }

    // private fun distanceInMeters(a: LocationCoordinate2D, b: LocationCoordinate2D): Double { // 현재 직접 사용 안함
    //     return sqrt((a.longitude - b.longitude).pow(2.0) + (a.latitude - b.latitude).pow(2.0)) * 111139.0
    // }

    private fun executeNextWaypointMission() {
        if (waypointTracker >= waypoints.size) {
            Log.d(TAG, "모든 웨이포인트 방문 완료. 미션 종료.")
            completeMissionExecution()
            return
        }

        currentWaypoint = waypoints[waypointTracker]
        // Yaw 값을 0으로 유지하면 기체는 이륙 시의 방향을 기준으로 정면을 유지합니다.
        // 만약 각 웨이포인트로 이동할 때 기체의 정면도 이동 방향을 향하게 하려면
        // 여기에서 다음 웨이포인트 방향으로 yaw 값을 계산하고 설정해야 합니다.
        // 이는 LocationObserver에서 pitch, roll 계산 시 함께 yaw도 계산하도록 수정 필요.
        // YawControlMode도 ANGULAR_VELOCITY로 변경해야 할 수 있습니다.
        directions = Direction(altitude = currentWaypoint.altitude, yaw = 0f) // yaw = 0f는 초기 이륙 방향 기준
        state = WaypointMissionState.EXECUTING
        isHoveringForPhoto = false
        photoTakenToggle = false
        originalLatitudeDiff = -1.0
        originalLongitudeDiff = -1.0
        travelledLatitude = false
        travelledLongitude = false

        Log.d(TAG, "${waypointTracker + 1}번째 웨이포인트로 이동 시작: (${currentWaypoint.coordinate.latitude}, ${currentWaypoint.coordinate.longitude}), 고도: ${currentWaypoint.altitude}m")

        if (!droneLocationLiveData.hasObservers()) {
            droneLocationLiveData.observe(activity, locationObserver)
        }
    }

    private val locationObserver = Observer<LocationCoordinate3D> { currentLocation ->
        if (state != WaypointMissionState.EXECUTING) {
            return@Observer
        }
        if (!::currentWaypoint.isInitialized) {
            Log.e(TAG, "currentWaypoint가 초기화되지 않아 위치 관찰자 로직을 실행할 수 없습니다.")
            return@Observer
        }

        val longitudeDiff = currentWaypoint.coordinate.longitude - currentLocation.longitude
        val latitudeDiff = currentWaypoint.coordinate.latitude - currentLocation.latitude

        if (travelledLatitude && travelledLongitude) {
            if (!isHoveringForPhoto && !photoTakenToggle) {
                isHoveringForPhoto = true
                Log.d(TAG, "웨이포인트 ${waypointTracker + 1} 도달. 사진 촬영 위해 ${HOVER_DURATION_MS}ms 호버링 시작.")
                // 호버링 시 yaw는 이전 값을 유지하거나 0으로 설정
                move(Direction(0f, 0f, directions.yaw, currentWaypoint.altitude))
                sendDataTimer.cancel()

                Handler(Looper.getMainLooper()).postDelayed({
                    if (isHoveringForPhoto) {
                        Log.d(TAG, "호버링 완료. 사진 촬영 시도.")
                        photoTakenToggle = true
                        takePhoto() // 사진 촬영 후 proceedToNextWaypointAfterAction 호출됨
                    }
                }, HOVER_DURATION_MS)
            }
            return@Observer
        }

        if (originalLatitudeDiff < 0 || abs(latitudeDiff) > originalLatitudeDiff) originalLatitudeDiff = abs(latitudeDiff)
        if (originalLongitudeDiff < 0 || abs(longitudeDiff) > originalLongitudeDiff) originalLongitudeDiff = abs(longitudeDiff)

        sendDataTimer.cancel()
        sendDataTimer = Timer()

        var movePitch = 0f
        var moveRoll = 0f
        val MIN_SPEED_NEAR_TARGET = 0.2f
        val MAX_SPEED_CONTROL = mission.autoFlightSpeed

        if (!travelledLongitude) {
            var speedFactorLon = if (originalLongitudeDiff > 0.000001) abs(longitudeDiff) / originalLongitudeDiff else 0.0
            if (abs(longitudeDiff) < REACH_THRESHOLD_DEGREES * 10) { // 목표 근처 감속 로직
                speedFactorLon *= 0.6
            }
            val dynamicSpeedLon = kotlin.math.max((MAX_SPEED_CONTROL * speedFactorLon).toFloat(), MIN_SPEED_NEAR_TARGET)
            // Ground coordinate system: pitch controls forward/backward based on current drone heading
            movePitch = if (longitudeDiff > 0) dynamicSpeedLon else -dynamicSpeedLon // 이 부분은 GPS 기준이므로 실제론 roll/pitch 혼합 필요

            if (abs(longitudeDiff) < REACH_THRESHOLD_DEGREES) {
                Log.d(TAG, "경도 목표(${waypointTracker + 1})에 충분히 근접함.")
                movePitch = 0f
                travelledLongitude = true
            }
        } else {
            movePitch = 0f
        }

        if (!travelledLatitude) {
            var speedFactorLat = if (originalLatitudeDiff > 0.000001) abs(latitudeDiff) / originalLatitudeDiff else 0.0
            if (abs(latitudeDiff) < REACH_THRESHOLD_DEGREES * 10) { // 목표 근처 감속 로직
                speedFactorLat *= 0.6
            }
            val dynamicSpeedLat = kotlin.math.max((MAX_SPEED_CONTROL * speedFactorLat).toFloat(), MIN_SPEED_NEAR_TARGET)
            // Ground coordinate system: roll controls left/right based on current drone heading
            moveRoll = if (latitudeDiff > 0) dynamicSpeedLat else -dynamicSpeedLat // 이 부분은 GPS 기준이므로 실제론 roll/pitch 혼합 필요

            if (abs(latitudeDiff) < REACH_THRESHOLD_DEGREES) {
                Log.d(TAG, "위도 목표(${waypointTracker + 1})에 충분히 근접함.")
                moveRoll = 0f
                travelledLatitude = true
            }
        } else {
            moveRoll = 0f
        }

        // 중요: Ground Coordinate System에서 pitch/roll은 기체 기준입니다.
        // 위도/경도 차이로 직접 pitch/roll을 제어하면 기체의 현재 yaw 각도에 따라
        // 의도와 다른 방향으로 움직일 수 있습니다.
        // 정확한 제어를 위해서는 현재 드론의 yaw와 목표 지점 간의 각도를 계산하여
        // 해당 각도에 맞춰 pitch (전진) 값을 주고, roll은 0으로 두거나 미세 조정해야 합니다.
        // 또는, YawControlMode.ANGULAR_VELOCITY를 사용하고,
        // 매번 목표 방향으로 yaw를 회전시키면서 전진(pitch)해야 합니다.

        // 현재 코드는 yaw를 0 (이륙시 방향)으로 고정하고,
        // 위도/경도 차이에 따라 pitch/roll 값을 독립적으로 조절하고 있습니다.
        // 이는 드론이 항상 같은 방향을 바라보며 옆이나 뒤로 이동할 수 있음을 의미합니다.
        // 만약 드론이 항상 이동 방향을 바라보게 하려면 yaw 제어 로직 추가가 필요합니다.
        // 예시: directions.yaw = calculateYawToTarget(currentLocation, currentWaypoint.coordinate)

        directions.pitch = movePitch
        directions.roll = moveRoll
        // directions.yaw는 executeNextWaypointMission에서 설정된 값(현재 0f)을 유지
        directions.altitude = currentWaypoint.altitude

        move(directions)
    }


    private fun proceedToNextWaypointAfterAction() {
        Log.d(TAG, "액션(사진 촬영 시도) 완료. 다음 웨이포인트 ${waypointTracker + 2} 준비.")
        isHoveringForPhoto = false
        // photoTakenToggle = false; // 다음 웨이포인트에서 다시 false로 설정되므로 여기서 중복 필요 X

        activity.runOnUiThread {
            waypointTracker++
            executeNextWaypointMission()
        }
    }

    private fun completeMissionExecution() {
        Log.d(TAG, "미션 실행 완료. 정리 작업 시작.")
        if (state == WaypointMissionState.EXECUTION_STOPPING ) return

        state = WaypointMissionState.EXECUTION_STOPPING
        operatorListener?.onExecutionFinish(null)

        showToast(mContext, "모든 웨이포인트 완료. 착륙 시작.")
        DJIDemoApplication.getFlightController()?.startLanding { landingError ->
            if (landingError == null) {
                showToast(mContext, "자동 착륙 시작됨.")
            } else {
                showToast(mContext, "자동 착륙 실패: ${landingError.description}. RTL 시도.")
                DJIDemoApplication.getFlightController()?.startGoHome(null)
            }
        }
        isLanding = true
        sendDataTimer.cancel()
        removeObservers()
    }

    private fun removeObservers() {
        Log.d(TAG, "모든 옵저버 제거")
        if (droneLocationLiveData.hasObservers()) {
            droneLocationLiveData.removeObserver(locationObserver)
        }
        // 짐벌 옵저버 로직 제거됨
        // gimbalObserver?.let {
        //     if (gimbalPitchLiveData.hasObservers()) {
        //         gimbalPitchLiveData.removeObserver(it)
        //     }
        //     gimbalObserver = null
        // }
    }

    @SuppressLint("LongLogTag")
    private fun move(dir: Direction) {
        // Log.d(TAG, "Move: P:${dir.pitch}, R:${dir.roll}, Y:${dir.yaw}, Alt:${dir.altitude}")
        DJIDemoApplication.getFlightController()?.sendVirtualStickFlightControlData(
            FlightControlData(dir.pitch, dir.roll, dir.yaw, dir.altitude),
            null
        )
    }

    fun stopMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?) {
        Log.d(TAG, "미션 중지 명령 수신. 현재 상태: $state")
        if (state == WaypointMissionState.EXECUTION_STOPPING || state == WaypointMissionState.EXECUTION_PAUSED) { // PAUSED 상태도 고려
            callback?.onResult(null)
            return
        }

        state = WaypointMissionState.EXECUTION_STOPPING
        sendDataTimer.cancel()
        removeObservers()
        // 현재 진행중인 move 명령 중단 (값을 0으로 설정하여 전송)
        move(Direction(0f,0f,0f, droneLocationLiveData.value?.altitude ?: currentWaypoint.altitude))


        if (!isLanding) { // isLanding은 자동 착륙/RTL 시작 여부
            showToast(mContext, "미션 중지. 귀환/착륙 시작.")
            isLanding = true // 중복 RTL/착륙 방지
            DJIDemoApplication.getFlightController()?.startGoHome { goHomeError ->
                if (goHomeError == null) {
                    showToast(mContext, "RTL 시작됨.")
                    Log.d(TAG, "RTL initiated by stopMission.")
                    // operatorListener?.onExecutionStopped() // WaypointMissionOperatorListener에 onExecutionStopped가 있다면 호출
                    callback?.onResult(null)
                } else {
                    showToast(mContext, "RTL 시작 실패: ${goHomeError.description}. 자동 착륙 시도.")
                    DJIDemoApplication.getFlightController()?.startLanding { landingError ->
                        Log.d(TAG, "Landing initiated by stopMission after RTL fail.")
                        // if (landingError == null) operatorListener?.onExecutionStopped()
                        callback?.onResult(if (landingError == null) null else DJIMissionError.FAILED)
                    }
                }
            }
        } else {
            showToast(mContext, "이미 귀환/착륙 절차 진행 중입니다.")
            callback?.onResult(null)
        }
        // stopMission은 사용자에 의한 중단이므로, 실패 또는 특정 에러 코드로 finish를 알릴 수 있음
        operatorListener?.onExecutionFinish(DJIMissionError.FAILED) // << 여기가 수정된 부분입니다.
    }

    fun addListener(listener: WaypointMissionOperatorListener) {
        this.operatorListener = listener
    }

    fun removeListener() {
        this.operatorListener = null
    }

    inner class Direction(
        var pitch: Float = 0f, // 기체 전후 이동 (Ground Coordinate System)
        var roll: Float = 0f,  // 기체 좌우 이동 (Ground Coordinate System)
        var yaw: Float = 0f,   // 기체 회전 각도 (절대 각도)
        var altitude: Float = 0f // 고도 (절대 고도)
    )

    private fun showToast(context: Context, message: String) {
        (context as? AppCompatActivity)?.runOnUiThread {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } ?: run {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
            Log.w(TAG, "Toast: Context가 AppCompatActivity가 아니거나 UI 스레드가 아닙니다. Handler 사용 시도. 메시지: $message")
        }
    }
}