package cloud.filecoin.foc.cache

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Disk-backed piece cache with LRU eviction, TTL freshness, and quota accounting.
 *
 * This is the Android/Kotlin analog of `logos-storage-nim/storage/stores/repostore/store.nim`.
 * Where the Nim version uses LevelDB, we use the filesystem for payloads and a
 * tiny SQLite table for metadata (size, mtime, last-access, expiry) — the same
 * information LevelDB records in the nim `RepoStore`.
 *
 * Concurrency: internal mutations (put/remove/eviction) are serialized with a
 * process-wide [ReentrantLock]. Reads (get/hasBlock) are lock-free after the
 * metadata lookup. Suitable for typical mobile access patterns.
 *
 * Layout on disk:
 *   {cacheDir}/pieces/{pieceCid}          — stored bytes (retrieved, or transformed)
 *   {cacheDir}/tmp/                       — in-flight downloads and transforms
 *   {cacheDir}/meta.db                    — sqlite metadata
 */
internal class LocalStore(
    context: Context,
    private val config: Config,
) {
    private val piecesDir: File = File(config.cacheDir, "pieces").apply { mkdirs() }
    private val tmpDir: File = File(config.cacheDir, "tmp").apply {
        mkdirs()
        // Leftovers from a fetch or transform that was interrupted.
        listFiles()?.forEach { it.delete() }
    }
    private val meta = MetaDb(context, File(config.cacheDir, "meta.db"))
    private val lock = ReentrantLock()

    /** The quota in force: [Config.quotaBytes], or sized once from free space via [Config.autoQuota]. */
    val quotaBytes: Long = config.autoQuota?.let { auto ->
        val free = runCatching { config.cacheDir.usableSpace }.getOrDefault(0L)
        if (free <= 0L) config.quotaBytes else auto.resolve(meta.totalBytes(), free)
    } ?: config.quotaBytes

    /** True iff bytes for [pieceCid] are on disk and not past their TTL. */
    fun hasBlock(pieceCid: String): Boolean {
        val row = meta.find(pieceCid) ?: return false
        if (isExpired(row.expiresAt)) return false
        return fileFor(pieceCid).isFile
    }

    /**
     * The stored file for [pieceCid], or null. Bumps last-access on hit. The path stays valid until
     * the piece is evicted or removed; a stream already open on it keeps reading after that, since
     * deleting an open file on Android only unlinks it.
     */
    fun file(pieceCid: String): File? {
        val row = meta.find(pieceCid) ?: return null
        if (isExpired(row.expiresAt)) return null
        val f = fileFor(pieceCid)
        if (!f.isFile) {
            // Metadata says we have it but the file vanished; clean up.
            lock.withLock { meta.delete(pieceCid) }
            return null
        }
        meta.touch(pieceCid, Instant.now().toEpochMilli())
        return f
    }

    /** Returns bytes or null; does not touch the network. Bumps last-access on hit. */
    fun get(pieceCid: String): ByteArray? = file(pieceCid)?.readBytes()

    /** Streamed variant of [get]; caller is responsible for closing. */
    fun openStream(pieceCid: String): InputStream? = file(pieceCid)?.let(::FileInputStream)

    /** A fresh file in the cache's temp area, on the same filesystem as the pieces (so [install] can rename). */
    fun newTempFile(): File = File.createTempFile("piece-", ".tmp", tmpDir)

    /**
     * Atomically write [bytes] under [pieceCid], evicting older entries as needed
     * to stay under the quota. If eviction cannot free enough space, throws
     * [QuotaExceededException].
     */
    fun put(pieceCid: String, bytes: ByteArray) {
        val tmp = newTempFile()
        try {
            FileOutputStream(tmp).use { it.write(bytes) }
            install(pieceCid, tmp)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Moves [source] (from [newTempFile]) into place under [pieceCid], evicting older entries as
     * needed. On [QuotaExceededException] nothing is evicted and [source] is left where it is.
     */
    fun install(pieceCid: String, source: File): File = lock.withLock {
        val now = Instant.now().toEpochMilli()
        val expiresAt = if (config.blockTtl.isZero) Long.MAX_VALUE
                       else now + config.blockTtl.toMillis()

        val newSize = source.length()
        if (newSize > quotaBytes) {
            throw QuotaExceededException("Piece $pieceCid ($newSize bytes) exceeds quota $quotaBytes")
        }

        // Compute how much room we need after accounting for any existing copy.
        val existing = meta.find(pieceCid)?.sizeBytes ?: 0L
        val used = meta.totalBytes() - existing
        val budget = quotaBytes - used
        if (newSize > budget) evictUntil(newSize - budget, keep = pieceCid)

        val target = fileFor(pieceCid)
        if (!source.renameTo(target)) {
            throw RuntimeException("Failed to install piece $pieceCid into cache")
        }
        meta.upsert(pieceCid, newSize, now, expiresAt)
        target
    }

    /** Remove one piece; no-op if absent. */
    fun remove(pieceCid: String): Boolean = lock.withLock {
        val row = meta.find(pieceCid) ?: return@withLock false
        fileFor(pieceCid).delete()
        meta.delete(pieceCid)
        true
    }

    /** Summary suitable for the module's `space()` API. */
    fun space(): SpaceInfo = SpaceInfo(
        totalPieces = meta.count(),
        quotaMaxBytes = quotaBytes,
        quotaUsedBytes = meta.totalBytes(),
    )

    /** Wipe every cached piece; leaves the SQLite schema intact. */
    fun clear(): Unit = lock.withLock {
        meta.forEachCid { pieceCid -> fileFor(pieceCid).delete() }
        meta.deleteAll()
    }

    // ----------------------------------------------------------------- internals

    private fun fileFor(pieceCid: String): File = File(piecesDir, pieceCid)

    private fun isExpired(expiresAt: Long): Boolean =
        expiresAt != Long.MAX_VALUE && Instant.now().toEpochMilli() > expiresAt

    /**
     * LRU eviction: drop least-recently-accessed pieces until we've freed [needed] bytes. Checks
     * first, so a piece that can't fit evicts nothing. [keep] is the piece being replaced.
     */
    private fun evictUntil(needed: Long, keep: String) {
        var available = 0L
        meta.forEachByLruAscending { row ->
            if (row.pieceCid != keep) available += row.sizeBytes
            available < needed
        }
        if (available < needed) {
            throw QuotaExceededException(
                "Cannot free $needed bytes under quota $quotaBytes; only $available evictable"
            )
        }
        var freed = 0L
        meta.forEachByLruAscending { row ->
            if (freed >= needed) return@forEachByLruAscending false
            if (row.pieceCid == keep) return@forEachByLruAscending true
            fileFor(row.pieceCid).delete()
            meta.delete(row.pieceCid)
            freed += row.sizeBytes
            true // keep going
        }
    }

    // ----------------------------------------------------------------- metadata

    internal data class MetaRow(
        val pieceCid: String,
        val sizeBytes: Long,
        val lastAccessMs: Long,
        val expiresAt: Long, // epoch ms, or Long.MAX_VALUE for "never"
    )

    /** Tiny SQLite table. Kept in its own class so it can be swapped for Room later. */
    private class MetaDb(context: Context, dbFile: File) {
        private val helper = object : SQLiteOpenHelper(
            context.applicationContext, dbFile.absolutePath, null, 1
        ) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE pieces (
                        piece_cid TEXT PRIMARY KEY,
                        size_bytes INTEGER NOT NULL,
                        last_access_ms INTEGER NOT NULL,
                        expires_at_ms INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX idx_last_access ON pieces(last_access_ms)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
                // v1 only; migrations go here when we bump.
            }
        }

        fun find(pieceCid: String): MetaRow? =
            helper.readableDatabase.rawQuery(
                "SELECT size_bytes, last_access_ms, expires_at_ms FROM pieces WHERE piece_cid = ?",
                arrayOf(pieceCid),
            ).use { c ->
                if (!c.moveToFirst()) null
                else MetaRow(pieceCid, c.getLong(0), c.getLong(1), c.getLong(2))
            }

        fun upsert(pieceCid: String, size: Long, lastAccess: Long, expiresAt: Long) {
            helper.writableDatabase.execSQL(
                """
                INSERT INTO pieces(piece_cid, size_bytes, last_access_ms, expires_at_ms)
                VALUES(?,?,?,?)
                ON CONFLICT(piece_cid) DO UPDATE SET
                    size_bytes = excluded.size_bytes,
                    last_access_ms = excluded.last_access_ms,
                    expires_at_ms = excluded.expires_at_ms
                """.trimIndent(),
                arrayOf<Any>(pieceCid as Any, size as Any, lastAccess as Any, expiresAt as Any),
            )
        }

        fun touch(pieceCid: String, lastAccess: Long) {
            helper.writableDatabase.execSQL(
                "UPDATE pieces SET last_access_ms = ? WHERE piece_cid = ?",
                arrayOf<Any>(lastAccess as Any, pieceCid as Any),
            )
        }

        fun delete(pieceCid: String) {
            helper.writableDatabase.execSQL(
                "DELETE FROM pieces WHERE piece_cid = ?", arrayOf(pieceCid),
            )
        }

        fun deleteAll() {
            helper.writableDatabase.execSQL("DELETE FROM pieces")
        }

        fun totalBytes(): Long =
            helper.readableDatabase.rawQuery("SELECT COALESCE(SUM(size_bytes),0) FROM pieces", null)
                .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

        fun count(): Long =
            helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM pieces", null)
                .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

        /** Iterate from least-recently-used to most; visitor returns false to stop. */
        fun forEachByLruAscending(visit: (MetaRow) -> Boolean) {
            helper.readableDatabase.rawQuery(
                "SELECT piece_cid, size_bytes, last_access_ms, expires_at_ms FROM pieces " +
                        "ORDER BY last_access_ms ASC",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val row = MetaRow(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3))
                    if (!visit(row)) return
                }
            }
        }

        fun forEachCid(visit: (String) -> Unit) {
            helper.readableDatabase.rawQuery("SELECT piece_cid FROM pieces", null).use { c ->
                while (c.moveToNext()) visit(c.getString(0))
            }
        }
    }
}

/** Thrown when a new piece cannot fit under the quota even after eviction. */
class QuotaExceededException(msg: String) : RuntimeException(msg)
