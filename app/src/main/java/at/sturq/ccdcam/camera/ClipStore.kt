package at.sturq.ccdcam.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections

/**
 * Where recordings live until they are finished. The Recorder writes into the app's private
 * `files/rec`; the clip is rotated and its sound processed there, and only the finished file is
 * published to the gallery (Movies/Handycam) in one go, as a pending MediaStore entry that
 * becomes visible when complete. The gallery never shows a half-processed clip, and a clip is
 * never rewritten in place. Files left behind by a killed process are published on next start.
 */
object ClipStore {
    private const val TAG = "ClipStore"
    private const val PROCESSED_SUFFIX = ".proc.mp4"

    /** Scene names (without .mp4) being recorded or finished by this process. */
    private val busy: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun recDir(context: Context): File = File(context.filesDir, "rec").apply { mkdirs() }

    fun recordingFile(context: Context, name: String): File {
        busy += name
        return File(recDir(context), "$name.mp4")
    }

    fun processedFile(context: Context, name: String): File = File(recDir(context), name + PROCESSED_SUFFIX)

    fun done(name: String) { busy -= name }

    /** A readable MP4 with at least one track (a recording cut off before its index is not). */
    fun isPlayable(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.path)
            ex.trackCount > 0
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { ex.release() }
        }
    }

    /** Writes the display rotation into [file] in place (36 bytes of its `tkhd`). */
    fun applyRotation(file: File, cwDegrees: Int): Boolean = RandomAccessFile(file, "rw").use { raf ->
        val store = object : ByteStore {
            override val size get() = raf.length()
            override fun read(position: Long, dst: ByteArray) { raf.seek(position); raf.readFully(dst) }
            override fun write(position: Long, src: ByteArray) { raf.seek(position); raf.write(src) }
        }
        Mp4Rotation.setVideoRotation(store, cwDegrees)
    }

    /**
     * Copies [file] into Movies/Handycam as [displayName]. The entry stays pending (invisible)
     * until the copy is complete; on failure it is removed and null is returned.
     */
    @Suppress("DEPRECATION") // MediaColumns.DATA is how pre-Q devices place the file
    fun publish(context: Context, file: File, displayName: String, takenMs: Long, moviesDir: String): Uri? {
        val resolver = context.contentResolver
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_TAKEN, takenMs)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, moviesDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val dir = File(Environment.getExternalStorageDirectory(), moviesDir).apply { mkdirs() }
                put(MediaStore.MediaColumns.DATA, File(dir, displayName).absolutePath)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        return try {
            resolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (t: Throwable) {
            Log.w(TAG, "publish $displayName failed", t)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /**
     * Recordings left in files/rec by a process that died before publishing them: playable
     * ones are published as recorded (rotation is written right after recording), the rest and
     * half-processed copies are deleted. Returns the published names.
     */
    fun recover(context: Context, moviesDir: String, takenOf: (String) -> Long): List<String> {
        val out = ArrayList<String>()
        for (f in recDir(context).listFiles().orEmpty()) {
            val name = f.name.removeSuffix(PROCESSED_SUFFIX).removeSuffix(".mp4")
            if (name in busy) continue
            if (f.name.endsWith(PROCESSED_SUFFIX) || !f.name.endsWith(".mp4")) { f.delete(); continue }
            if (!isPlayable(f)) { f.delete(); continue }
            if (publish(context, f, f.name, takenOf(name), moviesDir) != null) { out += name; f.delete() }
            // else: kept for the next start
        }
        return out
    }
}
