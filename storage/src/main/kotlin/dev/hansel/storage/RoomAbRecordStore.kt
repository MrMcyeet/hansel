package dev.hansel.storage

import dev.hansel.benchmark.AbRecord
import dev.hansel.benchmark.AbRecordStore
import kotlinx.serialization.json.Json

/** Room-backed [AbRecordStore]; the record is stored whole as JSON, with timestamps mirrored for ordering. */
class RoomAbRecordStore(private val db: HanselDatabase, private val json: Json = StorageJson) : AbRecordStore {
    private val dao get() = db.abRecords()

    override suspend fun upsert(record: AbRecord) {
        dao.upsert(
            AbRecordEntity(
                requestId = record.requestId,
                json = json.encodeToString(AbRecord.serializer(), record),
                createdAtMillis = record.createdAtMillis,
                completedAtMillis = record.completedAtMillis,
            ),
        )
    }

    override suspend fun get(requestId: String): AbRecord? = dao.get(requestId)?.let(::decode)

    /** Newest first, up to [limit]. */
    override suspend fun all(limit: Int): List<AbRecord> = dao.latest(limit).mapNotNull(::decode)

    suspend fun clear() = dao.deleteAll()

    private fun decode(e: AbRecordEntity): AbRecord? =
        runCatching { json.decodeFromString(AbRecord.serializer(), e.json) }.getOrNull()
}
