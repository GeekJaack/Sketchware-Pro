package pro.sketchware.ai.tools

import android.os.Environment
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

internal fun JsonObject.argStr(key: String): String {
    val el = get(key) ?: return ""
    return if (el.isJsonNull) "" else if (el.isJsonPrimitive) el.asString.trim() else el.toString()
}

internal fun JsonObject.argBool(key: String, default: Boolean = false): Boolean {
    val el = get(key) ?: return default
    if (el.isJsonNull) return default
    return try {
        if (el.asJsonPrimitive.isBoolean) el.asBoolean else el.asString.equals("true", ignoreCase = true)
    } catch (_: Exception) {
        default
    }
}

/** Reads a list of strings given as a JSON array, a JSON-array string, or a comma/newline separated string. */
internal fun JsonObject.argStringList(key: String): List<String> {
    val el = get(key) ?: return emptyList()
    if (el.isJsonNull) return emptyList()
    if (el.isJsonArray) {
        return el.asJsonArray.filter { it.isJsonPrimitive }.map { it.asString.trim() }.filter { it.isNotEmpty() }
    }
    val raw = el.asString.trim()
    if (raw.startsWith("[")) {
        try {
            return JsonParser.parseString(raw).asJsonArray.filter { it.isJsonPrimitive }.map { it.asString.trim() }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
        }
    }
    return raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}

internal object FileIo {
    /** Writes [text] via a temp file + rename so a crash never leaves a half-written project file. */
    fun atomicWrite(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }
}

internal object ProjectPaths {
    private val SC_ID = Regex("[0-9]+")

    fun requireScId(scId: String): String {
        require(SC_ID.matches(scId)) { "sc_id '$scId' tidak valid." }
        return scId
    }

    fun dataDir(scId: String): File =
        File(Environment.getExternalStorageDirectory(), ".sketchware/data/${requireScId(scId)}")
}
