package com.bob.voicerecorder

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : Service() {

    private var recorder: MediaRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val rotateHandler = Handler(Looper.getMainLooper())
    private var currentDayFolder: String? = null

    companion object {
        const val ACTION_START = "com.bob.voicerecorder.START"
        const val ACTION_STOP = "com.bob.voicerecorder.STOP"
        const val CHANNEL_ID = "rec_channel"
        const val NOTIF_ID = 1
        const val SEGMENT_MS = 60L * 60L * 1000L // 1 hour

        // Bitrate/sample rate tuned for small voice files
        const val SAMPLE_RATE = 16000
        const val BITRATE = 32000
    }

    private val rotateRunnable = object : Runnable {
        override fun run() {
            rotateSegment()
            rotateHandler.postDelayed(this, SEGMENT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    private fun startRecording() {
        createChannelIfNeeded()
        startForeground(NOTIF_ID, buildNotification())

        acquireWakeLock()
        beginNewSegmentFile(freshRecorder = true)

        // schedule hourly rotation
        rotateHandler.postDelayed(rotateRunnable, SEGMENT_MS)
    }

    private fun rotateSegment() {
        // stop current segment cleanly, then start a new file (new day folder if date rolled over)
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // recorder may throw if stopped too quickly after start; ignore, file is likely still valid
        }
        recorder = null
        beginNewSegmentFile(freshRecorder = true)
    }

    private fun beginNewSegmentFile(freshRecorder: Boolean) {
        val dayFolderName = dayFolderName()
        val dayDir = File(recordRoot(), dayFolderName)
        if (!dayDir.exists()) dayDir.mkdirs()
        currentDayFolder = dayFolderName

        val segFile = File(dayDir, segmentFileName())

        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(SAMPLE_RATE)
            setAudioEncodingBitRate(BITRATE)
            setOutputFile(segFile.absolutePath)
            prepare()
            start()
        }
    }

    private fun stopRecording() {
        rotateHandler.removeCallbacks(rotateRunnable)
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // ignore — last segment may be short
        }
        recorder = null
        releaseWakeLock()
        stopForeground(true)
        stopSelf()
    }

    // .record/VoiceM-D/  e.g. Voice8-3 for Aug 3rd
    private fun dayFolderName(): String {
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return "Voice$month-$day"
    }

    private fun segmentFileName(): String {
        val sdf = SimpleDateFormat("HHmmss", Locale.US)
        return "seg_${sdf.format(Date())}.m4a"
    }

    private fun recordRoot(): File {
        // App-private internal storage — not indexed by MediaStore, not visible
        // in Gallery/Files app/other apps' file pickers.
        val root = File(filesDir, ".record")
        if (!root.exists()) root.mkdirs()
        return root
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceRecorder::RecLock")
        wakeLock?.acquire() // held indefinitely until stopRecording() releases it
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recorder")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        rotateHandler.removeCallbacks(rotateRunnable)
        releaseWakeLock()
        super.onDestroy()
    }
}
