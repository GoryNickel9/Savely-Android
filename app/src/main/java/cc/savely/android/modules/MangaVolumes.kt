package cc.savely.android.modules

import cc.savely.android.data.SupabaseApi
import cc.savely.android.data.authSessionId
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.UUID

/** Source: Savely 1cfcf629, src/pages/libreria/Manga.tsx parseVolumeRange/handleSubmit. */
object MangaVolumes {
    const val MAX_COUNT = 100
    private val range = Regex("^([0-9]+)\\s*[-–]\\s*([0-9]+)$")
    private val single = Regex("^[0-9]+$")

    /** Blank retains ordinary item creation. Nonblank invalid input is never silently ignored. */
    fun parse(text: String): List<Int>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        require(trimmed.length <= 64) { "Intervallo troppo lungo" }
        fun positive(value: String) = value.toIntOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Usa un numero di volume positivo valido")
        val match = range.matchEntire(trimmed)
        if (match != null) {
            val start = positive(match.groupValues[1])
            val end = positive(match.groupValues[2])
            require(end >= start && end.toLong() - start < MAX_COUNT) { "Usa un intervallo crescente di massimo 100 volumi" }
            return (start..end).toList()
        }
        require(single.matches(trimmed)) { "Formato non valido. Usa 3 oppure 1-5" }
        return listOf(positive(trimmed))
    }

    fun prepare(ownerId: String, base: JSONObject, numbers: List<Int>): MangaVolumeBatch {
        require(numbers.isNotEmpty() && numbers.size <= MAX_COUNT && numbers.all { it > 0 }) { "Intervallo non valido" }
        require(numbers.zipWithNext().all { (a, b) -> b.toLong() == a.toLong() + 1 }) { "Intervallo non consecutivo" }
        val title = base.getString("title").trim()
        require(title.isNotEmpty()) { "Titolo obbligatorio" }
        val rows = numbers.map { volume ->
            val payload = JSONObject(base.toString()).put("id", UUID.randomUUID().toString())
                .put("user_id", ownerId).put("category", "manga").put("title", "$title Vol. $volume").put("quantity", 1)
            MangaPlannedVolume(volume, payload.toString())
        }
        return MangaVolumeBatch(ownerId, title, rows).also { it.encode() }
    }
}

/** JSON is immutable here; callers receive a new JSONObject for each request. */
data class MangaPlannedVolume(val number: Int, val payload: String) {
    fun body() = JSONObject(payload)
}

/** A sequential, non-atomic insertion plan. IDs remain fixed even after an ambiguous response. */
@ConsistentCopyVisibility
data class MangaVolumeBatch internal constructor(
    val ownerId: String,
    val baseTitle: String,
    val volumes: List<MangaPlannedVolume>,
    val completed: Int = 0,
    val reconcileCurrent: Boolean = false,
) {
    val total get() = volumes.size
    val complete get() = completed == total
    fun encode(): String {
        validate()
        val rows = JSONArray()
        volumes.forEach { rows.put(JSONObject().put("number", it.number).put("payload", it.body())) }
        val encoded = JSONObject().put("version", VERSION).put("owner", ownerId).put("title", baseTitle)
            .put("completed", completed).put("reconcile", reconcileCurrent).put("volumes", rows).toString()
        require(encoded.length <= MAX_SNAPSHOT_CHARS) { "Serie troppo grande: riduci titolo e note" }
        return encoded
    }

    private fun validate() {
        uuid(ownerId)
        require(baseTitle.isNotBlank() && volumes.size in 1..MangaVolumes.MAX_COUNT && completed in 0..total)
        require(!complete || !reconcileCurrent)
        require(volumes.zipWithNext().all { (a, b) -> b.number.toLong() == a.number.toLong() + 1 })
        val ids = mutableSetOf<String>()
        volumes.forEach { planned ->
            require(planned.number > 0)
            val body = planned.body()
            val id = body.getString("id")
            uuid(id)
            require(ids.add(id)) { "ID di volume duplicato" }
            require(body.getString("user_id") == ownerId && body.getString("category") == "manga")
            require(body.getString("title") == "$baseTitle Vol. ${planned.number}")
            require(body.opt("quantity") is Number && body.getDouble("quantity") == 1.0)
            require(body.keys().asSequence().all { it in PAYLOAD_KEYS }) { "Campo manga non valido" }
            listOf("author", "publisher", "cover_image", "api_id", "notes").forEach { key ->
                require(body.isNull(key) || body.get(key) is String) { "Metadato manga non valido" }
            }
            if (!body.isNull("year")) require(body.get("year") is Number && body.getDouble("year") % 1 == 0.0 &&
                body.getDouble("year") in 0.0..Int.MAX_VALUE.toDouble()) { "Anno non valido" }
            listOf("purchase_price", "reselling_value").forEach { key ->
                if (!body.isNull(key)) {
                    require(body.get(key) is Number) { "Prezzo non valido" }
                    val price = BigDecimal(body.get(key).toString())
                    require(price.signum() >= 0 && price <= MAX_PRICE && price.stripTrailingZeros().scale() <= 2) {
                        "Usa prezzi unitari validi con massimo due decimali"
                    }
                }
            }
        }
    }

    companion object {
        private const val VERSION = 1
        private const val MAX_SNAPSHOT_CHARS = 131072
        private val MAX_PRICE = BigDecimal("99999999.99") // Existing library_items numeric(10,2).
        private val PAYLOAD_KEYS = setOf("id", "user_id", "category", "title", "author", "publisher", "year",
            "cover_image", "api_id", "quantity", "purchase_price", "reselling_value", "notes")
        private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) { "ID non valido" } }
        fun decode(encoded: String): MangaVolumeBatch {
            require(encoded.length <= MAX_SNAPSHOT_CHARS) { "Serie locale troppo grande" }
            val json = JSONObject(encoded)
            require(json.opt("version") == VERSION) { "Versione serie locale non supportata" }
            val rows = json.getJSONArray("volumes")
            require(rows.length() in 1..MangaVolumes.MAX_COUNT)
            fun integer(obj: JSONObject, key: String): Int {
                val value = obj.get(key)
                require(value is Number && value.toDouble().isFinite() && value.toDouble() % 1 == 0.0 &&
                    value.toDouble() in 0.0..Int.MAX_VALUE.toDouble()) { "Serie locale non valida" }
                return value.toInt()
            }
            require(json.get("reconcile") is Boolean)
            val planned = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                MangaPlannedVolume(integer(row, "number"), row.getJSONObject("payload").toString())
            }
            return MangaVolumeBatch(json.getString("owner"), json.getString("title"), planned,
                integer(json, "completed"), json.getBoolean("reconcile")).also { it.validate() }
        }
    }
}

class MangaVolumeRepository(private val api: SupabaseApi) {
    /** Progress is published before POST, so restored state knows which response may be ambiguous. */
    suspend fun save(initial: MangaVolumeBatch, expectedOwner: String, expectedRevision: Long, expectedAuthSessionId: String? = null, progress: (MangaVolumeBatch) -> Unit): MangaVolumeBatch {
        initial.encode()
        var batch = initial
        suspend fun checkOwner() {
            val snapshot = api.session()
            require(batch.ownerId == expectedOwner && snapshot != null && snapshot.userId == expectedOwner &&
                api.sessionOwnerRevision(snapshot) == expectedRevision &&
                (expectedAuthSessionId == null || snapshot.authSessionId() == expectedAuthSessionId)) {
                "Account cambiato: chiudi la serie e controlla la collezione"
            }
        }
        suspend fun reconcile(body: JSONObject): Boolean {
            checkOwner()
            val result = api.select("library_items", "id=eq.${body.getString("id")}&user_id=eq.${expectedOwner}&limit=2")
            checkOwner()
            require(result.length() <= 1) { "Risposta non valida: controlla la collezione prima di riprovare" }
            if (result.length() == 0) return false
            require(matches(body, result.getJSONObject(0))) { "Il volume esistente non corrisponde alla serie: controlla la collezione" }
            return true
        }
        suspend fun confirmed() {
            checkOwner()
            api.withCurrentSessionOwner(expectedRevision) {
                batch = batch.copy(completed = batch.completed + 1, reconcileCurrent = false)
                progress(batch)
            }
            // An owner switch must not silently turn a missed atomic publication into success.
            checkOwner()
        }
        checkOwner()
        while (!batch.complete) {
            val body = batch.volumes[batch.completed].body()
            checkOwner()
            if (batch.reconcileCurrent && reconcile(body)) { confirmed(); continue }
            batch = batch.copy(reconcileCurrent = true)
            progress(batch)
            try {
                checkOwner()
                val inserted = api.insert("library_items", body, expectedOwner = expectedOwner, expectedRevision = expectedRevision)
                checkOwner()
                require(matches(body, inserted)) { "Risposta di salvataggio non verificabile" }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                // Read after any ambiguous response; never automatically retry a POST in this attempt.
                try {
                    if (reconcile(body)) { confirmed(); continue }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (verification: Exception) {
                    throw IllegalStateException("${batch.completed}/${batch.total} volumi confermati. Verifica non riuscita: ${verification.message}", verification)
                }
                throw IllegalStateException("${batch.completed}/${batch.total} volumi confermati. Salvataggio interrotto: ${failure.message}. Premi Riprendi per verificare e continuare.", failure)
            }
            confirmed()
        }
        return batch
    }

    private fun matches(expected: JSONObject, actual: JSONObject): Boolean = expected.keys().asSequence().all { key ->
        if (!actual.has(key)) return@all false
        val wanted = expected.get(key)
        val received = actual.get(key)
        when {
            wanted == JSONObject.NULL -> received == JSONObject.NULL
            wanted is Number && received is Number -> runCatching {
                BigDecimal(wanted.toString()).compareTo(BigDecimal(received.toString())) == 0
            }.getOrDefault(false)
            else -> wanted == received
        }
    }
}
