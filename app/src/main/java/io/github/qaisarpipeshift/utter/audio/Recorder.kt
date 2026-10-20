package io.github.qaisarpipeshift.utter.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.diag.Diag
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Result of opening the microphone; a failure is a value, never an exception (the panel shows it as a Retry). */
sealed interface RecorderStart {
    data object Ok : RecorderStart
    data class Error(val code: Int, val why: String) : RecorderStart
}

/**
 * Microphone access for one take (docs/plan.md §2, Task 6).
 *
 * [start] opens the mic and returns once audio is flowing; [stop] returns the take's duration after the mic is
 * released; [cancel] discards the file. Implementations must never hold the microphone after any of them returns.
 */
interface TakeRecorder {
    /** Opens the mic, writes PCM16LE 16 kHz mono to [out], calls [onChunk] every 100 ms. */
    suspend fun start(out: File, onChunk: (ByteArray, Float) -> Unit): RecorderStart

    /** Stops, releases the mic, and returns the captured duration in milliseconds. */
    suspend fun stop(): Long

    /** Stops, releases the mic, and deletes the take file. */
    fun cancel()
}

/**
 * [TakeRecorder] on `AudioRecord` (docs/spec.md §2, Task 6).
 *
 * Why `VOICE_RECOGNITION`: it applies the noise-suppression path tuned for ASR without the AGC that makes dictation
 * pump. The read loop lives on its own IO coroutine and owns the mic release (`finally`), so an error, a stop or a
 * cancel all end with a released `AudioRecord` even if the caller forgets to stop.
 */
class AndroidRecorder(
    private val context: Context,
    private val settings: () -> Settings,
    private val diag: Diag = Diag(),
) : TakeRecorder {

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running = false

    private var record: AudioRecord? = null
    private var stream: BufferedOutputStream? = null
    private var output: File? = null
    private var job: Job? = null
    private var bytesWritten = 0L

    override suspend fun start(out: File, onChunk: (ByteArray, Float) -> Unit): RecorderStart =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (record != null) return@withContext RecorderStart.Error(BUSY, "already recording")
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return@withContext RecorderStart.Error(NO_PERMISSION, "no permission")
            }

            val minBuffer = AudioRecord.getMinBufferSize(
                Pcm.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferBytes = maxOf(minBuffer, CHUNK_SAMPLES * Pcm.BYTES_PER_SAMPLE * READ_BUFFER_FACTOR)
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    Pcm.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (failure: Exception) {
                diag.event("recorder_create", failure.javaClass.simpleName)
                return@withContext RecorderStart.Error(CREATE_FAILED, "create failed")
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                val code = recorder.state
                recorder.release()
                diag.event("recorder_state", code.toString())
                return@withContext RecorderStart.Error(code, "not initialized")
            }

            settings().micId?.let { id ->
                preferredInput(id)?.let { device ->
                    try {
                        recorder.setPreferredDevice(device)
                    } catch (_: IllegalArgumentException) {
                        // The device disappeared between listing and starting; the default mic is fine.
                    }
                }
            }

            val started = try {
                recorder.startRecording()
                recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
            } catch (_: Exception) {
                false
            }
            if (!started) {
                val code = recorder.recordingState
                try {
                    recorder.stop()
                } catch (_: IllegalStateException) {
                    // Not recording — release is all that is left.
                }
                recorder.release()
                diag.event("recorder_start", code.toString())
                return@withContext RecorderStart.Error(code, "start failed")
            }

            val buffered = try {
                BufferedOutputStream(FileOutputStream(out), STREAM_BUFFER_BYTES)
            } catch (failure: IOException) {
                recorder.release()
                diag.event("recorder_file", failure.javaClass.simpleName)
                return@withContext RecorderStart.Error(FILE_FAILED, "file failed")
            }

            synchronized(lock) {
                record = recorder
                stream = buffered
                output = out
                bytesWritten = 0
            }
            running = true
            launchReadLoop(recorder, buffered, onChunk)
            RecorderStart.Ok
        }

    override suspend fun stop(): Long = withContext(Dispatchers.IO) {
        val tail = settings().tailMs.coerceIn(0, MAX_TAIL_MS)
        if (tail > 0 && running) delay(tail.toLong())
        running = false
        val current = synchronized(lock) { job }
        current?.join()
        synchronized(lock) { job = null }
        releaseRecording()
        val written = synchronized(lock) { bytesWritten }
        closeStream(delete = false)
        Pcm.durationMs(written, Pcm.SAMPLE_RATE)
    }

    override fun cancel() {
        running = false
        val current = synchronized(lock) { job }
        // Stopping the recorder unblocks a read that is waiting for its 100 ms of audio before join can finish.
        stopRecording()
        scope.launch {
            withTimeoutOrNull(JOIN_TIMEOUT_MS) { current?.join() }
            synchronized(lock) { job = null }
            releaseRecording()
            closeStream(delete = true)
        }
    }

    private fun launchReadLoop(
        recorder: AudioRecord,
        buffered: BufferedOutputStream,
        onChunk: (ByteArray, Float) -> Unit,
    ) {
        job = scope.launch {
            val samples = ShortArray(CHUNK_SAMPLES)
            val bytes = ByteArray(CHUNK_SAMPLES * Pcm.BYTES_PER_SAMPLE)
            try {
                while (isActive && running) {
                    val read = recorder.read(samples, 0, samples.size)
                    if (read < 0) {
                        diag.event("recorder_read", read.toString())
                        break
                    }
                    if (read == 0) continue
                    val byteCount = read * Pcm.BYTES_PER_SAMPLE
                    for (index in 0 until read) {
                        val value = samples[index].toInt()
                        bytes[index * 2] = (value and 0xFF).toByte()
                        bytes[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                    }
                    buffered.write(bytes, 0, byteCount)
                    bytesWritten += byteCount
                    val chunk = bytes.copyOf(byteCount)
                    onChunk(chunk, Pcm.rms(chunk))
                }
            } catch (failure: IOException) {
                diag.event("recorder_io", failure.javaClass.simpleName)
            } finally {
                running = false
                releaseRecording()
            }
        }
    }

    private fun preferredInput(id: Int): AudioDeviceInfo? {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
    }

    private fun stopRecording() {
        val recorder = synchronized(lock) { record } ?: return
        try {
            recorder.stop()
        } catch (_: IllegalStateException) {
            // Already stopped by the read loop's finally.
        }
    }

    private fun releaseRecording() {
        val recorder = synchronized(lock) { record.also { record = null } } ?: return
        try {
            recorder.stop()
        } catch (_: IllegalStateException) {
            // Not recording; releasing is still required.
        }
        try {
            recorder.release()
        } catch (_: Exception) {
            diag.event("recorder_release", "failed")
        }
    }

    private fun closeStream(delete: Boolean) {
        val buffered = synchronized(lock) { stream.also { stream = null } }
        try {
            buffered?.flush()
        } catch (_: IOException) {
            // The take is already on disk as far as it got; nothing to recover.
        }
        try {
            buffered?.close()
        } catch (_: IOException) {
            diag.event("recorder_close", "failed")
        }
        val file = synchronized(lock) { output.also { output = null } }
        if (delete) file?.delete()
        synchronized(lock) { bytesWritten = 0 }
    }

    private companion object {
        const val CHUNK_SAMPLES = 1_600
        const val READ_BUFFER_FACTOR = 4
        const val STREAM_BUFFER_BYTES = 64 * 1024
        const val MAX_TAIL_MS = 500
        const val JOIN_TIMEOUT_MS = 1_000L

        const val NO_PERMISSION = -1
        const val CREATE_FAILED = -2
        const val BUSY = -3
        const val FILE_FAILED = -4
    }
}
