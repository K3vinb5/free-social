package dev.k3vinb5.freesocial.filter

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One node of an exported dump; same shape as `uiautomator dump` so the two can be diffed. */
@Serializable
data class DumpNode(
    val viewId: String? = null,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val bounds: String = "",
    val visible: Boolean = false,
    val selected: Boolean = false,
    val clickable: Boolean = false,
    /** D8: true when the node was reached by a lookup graft, not by the child walk. */
    val grafted: Boolean = false,
)

/** Per-window capture stats written into the dump header (D6). */
@Serializable
data class DumpWindow(
    val title: String? = null,
    val className: String? = null,
    val layer: Int = 0,
    val packageName: String? = null,
    val nodes: Int = 0,
    val viewIds: Int = 0,
)

/** The rule-inspector export (SPEC §2.5). Header stats make an empty/ID-stripped dump obvious. */
@Serializable
data class DumpDocument(
    @SerialName("package") val appPackage: String,
    val screen: String,
    /** Verified surface slug, or null when the markers did not match (surface-verified capture). */
    val surface: String? = null,
    val surfaceVerified: Boolean = false,
    /** What was on screen when verification failed (bounded viewId/text/desc lines). */
    val markersSeen: List<String> = emptyList(),
    val capturedAt: String,
    val windows: List<DumpWindow> = emptyList(),
    val nodeCount: Int = 0,
    val nodesWithViewId: Int = 0,
    /** D8: nodes recovered by the id/text lookup grafts (same number as the scan's `grafted=`). */
    val grafted: Int = 0,
    val truncated: Boolean = false,
    val nodes: List<DumpNode> = emptyList(),
)

/** One file already on disk, as shown in the inspector list. */
data class DumpFile(
    val file: File,
    val document: DumpDocument,
    val nodeCount: Int,
)

sealed interface DumpResult {
    data class Success(val file: File?, val document: DumpDocument, val json: String) : DumpResult

    /** No accessibility service / no readable target window — nothing was written. */
    data class Unavailable(val reason: String) : DumpResult
}

/**
 * Walks the current window tree into `getExternalFilesDir(null)/dumps/<pkg>-<timestamp>.json`.
 *
 * This is the device gap that `uiautomator dump` cannot cover (SPEC §2.5): the service can read
 * surfaces while they play, which is exactly where the rules need to come from.
 */
object DumpExporter {
    const val DIR_NAME = "dumps"

    private const val TAG = "DumpExporter"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /** Builds the document for one captured tree; pure apart from the timestamp. */
    fun document(
        appPackage: String,
        screen: String,
        tree: NodeSnapshot,
        now: ZonedDateTime = ZonedDateTime.now(),
        windowStats: List<WindowStat> = emptyList(),
        capped: Boolean = false,
        grafted: Int = 0,
        verdict: CaptureSurfaces.Verdict? = null,
    ): DumpDocument = DumpDocument(
        appPackage = appPackage,
        screen = verdict?.label ?: screen,
        surface = verdict?.takeIf { it.verified }?.surface,
        surfaceVerified = verdict?.verified == true,
        markersSeen = verdict?.seen.orEmpty(),
        capturedAt = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        windows = windowStats.map { window ->
            DumpWindow(
                title = window.title,
                className = window.className,
                layer = window.layer,
                packageName = window.packageName,
                nodes = window.nodes,
                viewIds = window.viewIds,
            )
        },
        nodeCount = NodeTree.flatten(tree, NodeTree.MAX_SCAN_NODES).size,
        nodesWithViewId = NodeTree.flatten(tree, NodeTree.MAX_SCAN_NODES).count { it.viewId != null },
        grafted = grafted,
        truncated = capped,
        nodes = NodeTree.flatten(tree, NodeTree.MAX_SCAN_NODES).map { node ->
            DumpNode(
                viewId = node.viewId,
                className = node.className,
                text = node.text,
                contentDescription = node.contentDescription,
                bounds = boundsText(node.bounds),
                visible = node.visible,
                selected = node.selected,
                clickable = node.clickable,
                grafted = node.grafted,
            )
        },
    )

    /** The exact JSON text written to disk / shown in the inspector (pure serialization). */
    fun encode(document: DumpDocument): String = json.encodeToString(DumpDocument.serializer(), document)

    /** Writes [tree] to the dumps directory; the file is null when the write failed. */
    fun export(
        context: Context,
        appPackage: String,
        screen: String,
        tree: NodeSnapshot,
        now: ZonedDateTime = ZonedDateTime.now(),
        windowStats: List<WindowStat> = emptyList(),
        capped: Boolean = false,
        grafted: Int = 0,
        verdict: CaptureSurfaces.Verdict? = null,
    ): DumpResult {
        val doc = document(appPackage, screen, tree, now, windowStats, capped, grafted, verdict)
        val text = encode(doc)
        val file = try {
            writeFile(context, appPackage, text, now)
        } catch (t: Throwable) {
            Log.w(TAG, "could not write dump for $appPackage", t)
            null
        }
        if (file != null) prune(context, keeping = file.name)
        return DumpResult.Success(file, doc, text)
    }

    /** Dumps already written by [FilterService]'s armed capture, newest first, after housekeeping. */
    fun list(context: Context): List<DumpFile> {
        val dir = dumpDir(context)
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") } ?: return emptyList()
        val dumps = files.mapNotNull(::read)
        val deleted = deletePruned(dir, dumps, keeping = null)
        return dumps
            .filterNot { it.file.name in deleted }
            .sortedByDescending { it.document.capturedAt }
    }

    /**
     * Applies [DumpPruner] to the dumps directory, never deleting [keeping] (the file being
     * written). Housekeeping is best-effort: an IO failure is logged and capture carries on.
     */
    fun prune(context: Context, keeping: String? = null) {
        try {
            val dir = dumpDir(context)
            val dumps = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
                .orEmpty()
                .mapNotNull(::read)
            deletePruned(dir, dumps, keeping)
        } catch (t: Throwable) {
            Log.w(TAG, "could not prune old dumps", t)
        }
    }

    /** Maps one dump's header to the pruner's input; a bad `capturedAt` falls back to file time. */
    internal fun pruneEntry(fileName: String, document: DumpDocument, fallbackMillis: Long): DumpPruner.Entry {
        val timestamp = runCatching { ZonedDateTime.parse(document.capturedAt).toInstant() }
            .getOrElse { Instant.ofEpochMilli(fallbackMillis) }
        return DumpPruner.Entry(fileName, document.appPackage, document.surface, timestamp)
    }

    /** Deletes the names [DumpPruner] selects; returns the names actually removed. */
    private fun deletePruned(dir: File, dumps: List<DumpFile>, keeping: String?): Set<String> = try {
        val entries = dumps.map { pruneEntry(it.file.name, it.document, it.file.lastModified()) }
        val doomed = DumpPruner.select(entries, Instant.now(), keeping).toSet()
        val deleted = doomed.filter { File(dir, it).delete() }.toSet()
        if (deleted.isNotEmpty()) Log.i(TAG, "pruned ${deleted.size} old dumps")
        deleted
    } catch (t: Throwable) {
        Log.w(TAG, "could not prune old dumps", t)
        emptySet()
    }

    /** Parses one dump file; a half-written or foreign file is skipped, never thrown. */
    fun read(file: File): DumpFile? = try {
        val document = json.decodeFromString(DumpDocument.serializer(), file.readText())
        DumpFile(file, document, document.nodes.size)
    } catch (t: Throwable) {
        Log.w(TAG, "unreadable dump ${file.name}", t)
        null
    }

    fun shareIntent(document: DumpDocument, jsonText: String): Intent =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Free Social dump — ${document.appPackage}")
            .putExtra(Intent.EXTRA_TEXT, jsonText)

    /** `[l,t][r,b]`, the bounds format `uiautomator dump` emits. */
    fun boundsText(bounds: Bounds): String = "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"

    private fun dumpDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            error("cannot create $dir")
        }
        return dir
    }

    private fun writeFile(context: Context, appPackage: String, text: String, now: ZonedDateTime): File {
        val dir = dumpDir(context)
        val file = File(dir, "$appPackage-${now.format(STAMP)}.json")
        file.writeText(text)
        return file
    }
}
