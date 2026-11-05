package dev.k3vinb5.freesocial.filter

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
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
    val clickable: Boolean = false,
)

/** The rule-inspector export (SPEC §2.5). */
@Serializable
data class DumpDocument(
    @SerialName("package") val appPackage: String,
    val screen: String,
    val capturedAt: String,
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
    ): DumpDocument = DumpDocument(
        appPackage = appPackage,
        screen = screen,
        capturedAt = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        nodes = NodeTree.flatten(tree, NodeTree.MAX_SCAN_NODES).map { node ->
            DumpNode(
                viewId = node.viewId,
                className = node.className,
                text = node.text,
                contentDescription = node.contentDescription,
                bounds = boundsText(node.bounds),
                visible = node.visible,
                clickable = node.clickable,
            )
        },
    )

    /** Writes [tree] to the dumps directory; the file is null when the write failed. */
    fun export(
        context: Context,
        appPackage: String,
        screen: String,
        tree: NodeSnapshot,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): DumpResult {
        val doc = document(appPackage, screen, tree, now)
        val text = json.encodeToString(DumpDocument.serializer(), doc)
        val file = try {
            writeFile(context, appPackage, text, now)
        } catch (t: Throwable) {
            Log.w(TAG, "could not write dump for $appPackage", t)
            null
        }
        return DumpResult.Success(file, doc, text)
    }

    /** Dumps already written by [FilterService]'s armed capture, newest first. */
    fun list(context: Context): List<DumpFile> {
        val dir = dumpDir(context)
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull(::read).sortedByDescending { it.document.capturedAt }
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
