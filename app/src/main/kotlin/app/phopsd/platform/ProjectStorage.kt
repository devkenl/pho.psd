package app.phopsd.platform

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.phopsd.editor.EditorController
import app.phopsd.engine.io.LoadedProject
import app.phopsd.engine.io.ProjectFormatException
import app.phopsd.engine.io.ProjectReader
import app.phopsd.engine.io.ProjectWriter
import app.phopsd.engine.io.SaveInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Saves and opens `.phopsd` projects through the Storage Access Framework.
 *
 * SAF documents cannot be replaced atomically in general, so a save is staged:
 *  1. the project is written to an app-private temp file and fsync'd,
 *  2. the temp file is verified by re-reading it (structure + SHA-256 of all pixel data),
 *  3. if the destination already has content, it is backed up privately,
 *  4. the verified bytes are copied to the destination, which is verified again,
 *  5. on any failure after step 3 the backup is written back, so the user's previous
 *     file is never left half-written.
 */
class ProjectStorage(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val tempDir = File(context.cacheDir, "save-staging").apply { mkdirs() }
    private val backupDir = File(context.filesDir, "save-backups").apply { mkdirs() }

    suspend fun save(
        snapshot: EditorController.SaveSnapshot,
        destination: Uri,
        thumbnailPng: ByteArray?,
        appVersion: String,
        nowMillis: Long,
    ): Unit = withContext(Dispatchers.IO) {
        val temp = File(tempDir, "${UUID.randomUUID()}.phopsd.tmp")
        val backup = File(backupDir, "${destination.toString().hashCode().toUInt()}.phopsd.bak")
        try {
            // 1. stage
            FileOutputStream(temp).use { fos ->
                val out = BufferedOutputStream(fos, 1 shl 16)
                ProjectWriter.write(snapshot.document, snapshot.rasters, out, SaveInfo(appVersion, nowMillis), thumbnailPng)
                out.flush()
                fos.fd.sync()
            }
            // 2. verify staged copy
            FileInputStream(temp).use { ProjectReader.verify(BufferedInputStream(it, 1 shl 16)) }

            // 3. back up existing destination content
            val hadContent = copyFromUriIfNonEmpty(destination, backup)

            // 4. replace and verify the destination
            try {
                openForOverwrite(destination).use { out -> FileInputStream(temp).use { it.copyTo(out, 1 shl 16) } }
                openInput(destination).use { ProjectReader.verify(BufferedInputStream(it, 1 shl 16)) }
            } catch (e: Exception) {
                // 5. restore the previous version
                if (hadContent) {
                    runCatching { openForOverwrite(destination).use { out -> FileInputStream(backup).use { it.copyTo(out) } } }
                }
                throw e
            }
            backup.delete()
        } catch (e: ProjectFormatException) {
            throw StorageException("verification failed (${e.message})", e)
        } catch (e: SecurityException) {
            throw StorageException("permission to write this location was denied", e)
        } catch (e: IOException) {
            throw StorageException(e.message ?: "the file could not be written", e)
        } catch (e: OutOfMemoryError) {
            throw StorageException("not enough memory", e)
        } finally {
            temp.delete()
        }
    }

    suspend fun open(uri: Uri): LoadedProject = withContext(Dispatchers.IO) {
        try {
            openInput(uri).use { ProjectReader.read(BufferedInputStream(it, 1 shl 16)) }
        } catch (e: ProjectFormatException) {
            throw StorageException(e.message ?: "the project could not be read", e)
        } catch (e: SecurityException) {
            throw StorageException("pho.psd no longer has permission to read this file; open it again with File ▸ Open Project", e)
        } catch (e: java.io.FileNotFoundException) {
            throw StorageException("the file no longer exists or was moved", e)
        } catch (e: IOException) {
            throw StorageException(e.message ?: "the file could not be read", e)
        } catch (e: OutOfMemoryError) {
            throw StorageException("not enough memory to open this project", e)
        }
    }

    /** Keeps access to a user-chosen document across app restarts (Save and Recent need it). */
    fun persistAccess(uri: Uri, write: Boolean) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(uri, if (write) read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION else read)
        } catch (_: SecurityException) {
            // Write access may not be persistable (read-only provider): keep read access at least.
            runCatching { resolver.takePersistableUriPermission(uri, read) }
        }
    }

    fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun openInput(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("the file could not be opened")

    /** "wt" truncates; some providers only support "w", which truncates for new documents. */
    private fun openForOverwrite(uri: Uri): OutputStream =
        try {
            resolver.openOutputStream(uri, "wt")
        } catch (_: IllegalArgumentException) {
            resolver.openOutputStream(uri, "w")
        } catch (_: UnsupportedOperationException) {
            resolver.openOutputStream(uri, "w")
        } ?: throw IOException("the destination could not be opened for writing")

    private fun copyFromUriIfNonEmpty(uri: Uri, target: File): Boolean = try {
        openInput(uri).use { input ->
            val buffered = BufferedInputStream(input)
            buffered.mark(1)
            if (buffered.read() < 0) {
                false
            } else {
                buffered.reset()
                FileOutputStream(target).use { buffered.copyTo(it, 1 shl 16) }
                true
            }
        }
    } catch (_: java.io.FileNotFoundException) {
        false
    }
}
