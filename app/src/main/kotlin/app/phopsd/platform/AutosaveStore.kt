package app.phopsd.platform

import android.content.Context
import app.phopsd.editor.EditorController
import app.phopsd.editor.ProjectLink
import app.phopsd.engine.io.Json
import app.phopsd.engine.io.LoadedProject
import app.phopsd.engine.io.ProjectReader
import app.phopsd.engine.io.ProjectWriter
import app.phopsd.engine.io.SaveInfo
import app.phopsd.engine.io.int
import app.phopsd.engine.io.long
import app.phopsd.engine.io.objOrNull
import app.phopsd.engine.io.string
import app.phopsd.engine.io.stringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** What an autosave contains, shown on the home screen's recovery card. */
data class AutosaveMeta(
    val documentName: String,
    val width: Int,
    val height: Int,
    val savedAtMillis: Long,
    val project: ProjectLink?,
)

/**
 * App-private autosave journal for crash/kill recovery.
 *
 * Writes go to a temp file that is fsync'd and verified, then `rename`d over the previous
 * autosave — an atomic replace on the same filesystem — so the last good autosave survives
 * any interruption. At startup an existing autosave is moved aside to a *recovery* slot, so
 * a new editing session can never overwrite data the user has not yet chosen to recover.
 */
class AutosaveStore(context: Context) {
    private val dir = File(context.filesDir, "autosave").apply { mkdirs() }
    private val current = File(dir, "current.phopsd")
    private val currentMeta = File(dir, "current.json")
    private val recovery = File(dir, "recovery.phopsd")
    private val recoveryMeta = File(dir, "recovery.json")

    /** Called once at startup: turns a leftover autosave into a recoverable one. */
    fun promoteLeftoverToRecovery(): AutosaveMeta? {
        if (current.exists() && currentMeta.exists()) {
            // A newer leftover replaces an older unrecovered one.
            recovery.delete(); recoveryMeta.delete()
            current.renameTo(recovery)
            currentMeta.renameTo(recoveryMeta)
        } else {
            current.delete(); currentMeta.delete()
        }
        return recoveryMeta()
    }

    fun recoveryMeta(): AutosaveMeta? {
        if (!recovery.exists() || !recoveryMeta.exists()) return null
        return runCatching { parseMeta(recoveryMeta.readText()) }.getOrNull()
    }

    suspend fun loadRecovery(): LoadedProject = withContext(Dispatchers.IO) {
        FileInputStream(recovery).use { ProjectReader.read(BufferedInputStream(it, 1 shl 16)) }
    }

    fun discardRecovery() {
        recovery.delete(); recoveryMeta.delete()
    }

    suspend fun write(snapshot: EditorController.SaveSnapshot, meta: AutosaveMeta, appVersion: String) = withContext(Dispatchers.IO) {
        val tmp = File(dir, "current.phopsd.tmp")
        val tmpMeta = File(dir, "current.json.tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                val out = BufferedOutputStream(fos, 1 shl 16)
                ProjectWriter.write(snapshot.document, snapshot.rasters, out, SaveInfo(appVersion, meta.savedAtMillis))
                out.flush()
                fos.fd.sync()
            }
            FileInputStream(tmp).use { ProjectReader.verify(BufferedInputStream(it, 1 shl 16)) }
            FileOutputStream(tmpMeta).use { fos ->
                fos.write(Json.write(metaJson(meta)).toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            if (!tmp.renameTo(current) || !tmpMeta.renameTo(currentMeta)) error("autosave rename failed")
        } finally {
            tmp.delete(); tmpMeta.delete()
        }
    }

    /** The user saved or deliberately discarded the document: the autosave is no longer needed. */
    fun clearCurrent() {
        current.delete(); currentMeta.delete()
    }

    private fun metaJson(m: AutosaveMeta): Json = Json.obj(
        "documentName" to Json.str(m.documentName),
        "width" to Json.num(m.width),
        "height" to Json.num(m.height),
        "savedAt" to Json.num(m.savedAtMillis),
        "project" to m.project?.let { Json.obj("uri" to Json.str(it.uri), "name" to Json.str(it.displayName)) },
    )

    private fun parseMeta(text: String): AutosaveMeta {
        val o = Json.parse(text) as Json.Obj
        return AutosaveMeta(
            documentName = o.string("documentName"),
            width = o.int("width"),
            height = o.int("height"),
            savedAtMillis = o.long("savedAt"),
            project = o.objOrNull("project")?.let { p -> p.stringOrNull("uri")?.let { ProjectLink(it, p.string("name")) } },
        )
    }
}
