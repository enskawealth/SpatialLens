package com.spatiallens.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var recordButton: Button
    private lateinit var mainPreview: SurfaceView
    private lateinit var ultraPreview: SurfaceView

    private var spatialCapture: SpatialVideoCapture? = null
    private var isRecording = false
    private var mainSurface: Surface? = null
    private var ultraSurface: Surface? = null
    private var surfacesReady = false

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        recordButton = findViewById(R.id.recordButton)
        mainPreview = findViewById(R.id.mainPreview)
        ultraPreview = findViewById(R.id.ultraPreview)

        mainPreview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                mainSurface = holder.surface
                checkSurfacesReady()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                mainSurface = null
            }
        })

        ultraPreview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                ultraSurface = holder.surface
                checkSurfacesReady()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                ultraSurface = null
            }
        })

        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        }
    }

    private fun checkSurfacesReady() {
        if (mainSurface != null && ultraSurface != null && !surfacesReady) {
            surfacesReady = true
            initializeCamera()
        }
    }

    private fun initializeCamera() {
        statusText.text = "Finding cameras..."
        spatialCapture = SpatialVideoCapture(
            context = this,
            mainPreviewSurface = mainSurface!!,
            ultraPreviewSurface = ultraSurface!!
        )

        val found = spatialCapture!!.findCameras()
        if (!found) {
            statusText.text = "ERROR: No supported multi-camera found.\nRequires Pixel Pro device."
            recordButton.isEnabled = false
            return
        }

        statusText.text = "Cameras found. Opening..."
        try {
            spatialCapture!!.openCamera()
            statusText.text = "Ready to record spatial video"
            recordButton.isEnabled = true
        } catch (e: Exception) {
            statusText.text = "ERROR: "
            recordButton.isEnabled = false
        }
    }

    private fun startRecording() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        dir.mkdirs()
        val outputFile = File(dir, "SpatialVideo_.mp4")

        try {
            spatialCapture?.startRecording(outputFile.absolutePath)
            isRecording = true
            recordButton.text = "STOP RECORDING"
            statusText.text = "● RECORDING SPATIAL VIDEO..."
            recordButton.setBackgroundColor(getColor(android.R.color.holo_red_dark))
        } catch (e: Exception) {
            statusText.text = "ERROR starting: "
            Toast.makeText(this, "Failed to start: ", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        spatialCapture?.stopRecording()
        isRecording = false
        recordButton.text = "START RECORDING"
        statusText.text = "Saving video..."
        recordButton.setBackgroundColor(getColor(android.R.color.holo_blue_dark))

        recordButton.postDelayed({
            statusText.text = "Ready to record spatial video"
        }, 2000)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkSurfacesReady()
            } else {
                statusText.text = "Camera permission denied"
                recordButton.isEnabled = false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        spatialCapture?.release()
    }
}
