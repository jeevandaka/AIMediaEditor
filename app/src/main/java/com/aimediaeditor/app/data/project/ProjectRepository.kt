package com.aimediaeditor.app.data.project

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Projects are app-private state, not user media -- stored under internal
 * storage (never MediaStore, never external/shared), one JSON file per
 * project keyed by [com.aimediaeditor.app.editor.model.ProjectState.id].
 * Writes go to a temp file and are renamed into place (rename is atomic on
 * the same filesystem, which internal storage always is here) so a crash
 * mid-write can never leave a corrupt project file behind -- spec section
 * 22's "if the app crashes, don't lose user work" cuts both ways: the draft
 * must survive, but a half-written file must never be mistaken for a valid
 * one on the next read.
 */
class ProjectRepository(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val dir = File(context.filesDir, "projects").apply { mkdirs() }

    suspend fun save(record: ProjectRecord) = withContext(Dispatchers.IO) {
        val target = fileFor(record.project.id)
        val tmp = File(dir, "${record.project.id}.json.tmp")
        tmp.writeText(json.encodeToString(record))
        if (!tmp.renameTo(target)) {
            // Rename can fail across filesystems; internal storage never crosses one, but
            // don't silently drop the write if it somehow does.
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    suspend fun load(id: String): ProjectRecord? = withContext(Dispatchers.IO) {
        val file = fileFor(id)
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString<ProjectRecord>(file.readText()) }.getOrNull()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        fileFor(id).delete()
        Unit
    }

    /** A corrupt/partial file (interrupted write, not just the .tmp above) is skipped, not crashed on. */
    suspend fun listSummaries(): List<ProjectSummary> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f -> runCatching { json.decodeFromString<ProjectRecord>(f.readText()) }.getOrNull() }
            ?.map { it.toSummary() }
            ?.sortedByDescending { it.updatedAtMs }
            ?: emptyList()
    }

    private fun fileFor(id: String) = File(dir, "$id.json")
}
