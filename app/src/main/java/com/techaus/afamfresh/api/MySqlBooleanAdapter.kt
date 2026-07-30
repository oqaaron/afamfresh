package com.techaus.afamfresh.api

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * The single Gson configuration used to talk to the backend.
 *
 * Exposed so contract tests parse responses with exactly the same setup the app
 * ships — a test using a bare `Gson()` would not exercise the tinyint handling
 * below and would pass while the real app failed.
 */
internal fun afamFreshGson(): Gson = GsonBuilder()
    .setLenient()
    .registerTypeAdapter(Boolean::class.java, MySqlBooleanAdapter())
    .registerTypeAdapter(Boolean::class.javaObjectType, MySqlBooleanAdapter())
    .create()

/**
 * Reads MySQL `tinyint(1)` values as Booleans.
 *
 * WHY THIS IS NECESSARY
 *
 * The backend returns `SELECT *` rows straight through `json_encode`, and MySQL
 * has no boolean type — `is_read`, `is_verified`, `is_active`, `pickup_only` and
 * `is_weight_based` are all `tinyint(1)`, which PDO hands back as the integer 0
 * or 1. So the wire carries:
 *
 *     {"id": 192, "is_read": 1}
 *
 * Gson's built-in Boolean adapter rejects that outright:
 *
 *     JsonSyntaxException: Expected a boolean but was NUMBER at path $.is_read
 *
 * and because the exception aborts the whole document, ONE tinyint column makes
 * the entire response unparseable — the notification list, the vendor profile
 * and every surplus listing would come back as a network error with no clue why.
 *
 * The string case is worse than an error: PDO returns some columns as strings
 * depending on driver settings, and Gson's fallback there is
 * `Boolean.parseBoolean("1")`, which is **false**. That fails silently and would
 * mark every unread notification as read.
 *
 * This adapter therefore accepts all the representations the backend can emit:
 *
 *     1 / 0            (NUMBER — what PDO returns today)
 *     "1" / "0"        (STRING — emulated prepares)
 *     true / false     (BOOLEAN — hand-written PHP responses)
 *     "true" / "false" (STRING)
 *     "" / null        -> false
 *
 * Anything numeric and non-zero is true, matching SQL truthiness.
 */
internal class MySqlBooleanAdapter : TypeAdapter<Boolean>() {

    override fun write(out: JsonWriter, value: Boolean?) {
        if (value == null) out.nullValue() else out.value(value)
    }

    override fun read(reader: JsonReader): Boolean {
        return when (reader.peek()) {
            JsonToken.NULL -> {
                reader.nextNull()
                false
            }

            JsonToken.BOOLEAN -> reader.nextBoolean()

            // The common case: tinyint(1) arrives as 0 or 1.
            JsonToken.NUMBER -> reader.nextInt() != 0

            JsonToken.STRING -> {
                val raw = reader.nextString().trim()
                when {
                    raw.isEmpty() -> false
                    // Checked before parseBoolean, which would return false for "1".
                    raw.toIntOrNull() != null -> raw.toInt() != 0
                    else -> raw.equals("true", ignoreCase = true) ||
                        raw.equals("yes", ignoreCase = true)
                }
            }

            else -> {
                // Skip whatever this is rather than aborting the whole document.
                reader.skipValue()
                false
            }
        }
    }
}
