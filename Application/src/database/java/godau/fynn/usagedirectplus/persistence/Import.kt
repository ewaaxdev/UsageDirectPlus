package godau.fynn.usagedirectplus.persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Imports a database exported by [Export] into the current history database.
 *
 * Import is strictly additive: rows are inserted with INSERT OR IGNORE and existing rows are never
 * overwritten or deleted. App ids are remapped through the `apps` table, so databases exported from
 * other devices or installs merge cleanly.
 */
object Import {

    private const val TAG = "Import"

    data class Result(
        val apps: Int,
        val usageStats: Int,
        val usageIntervals: Int,
        val lastUsed: Int,
        val colors: Int,
        val screenEvents: Int,
        val browserTabSessions: Int,
        val notes: Int
    ) {
        val total: Int
            get() = usageStats + usageIntervals + lastUsed + colors +
                screenEvents + browserTabSessions + notes
    }

    /**
     * Merge the database behind [uri] into the current database.
     *
     * @throws IllegalArgumentException if the file is not a UsageDirectPlus database
     * @throws IOException on read/copy failures
     */
    fun merge(context: Context, uri: Uri): Result {
        val temp = File.createTempFile("import", ".sqlite3", context.cacheDir)
        try {
            copyToTempFile(context, uri, temp)
            return mergeFile(context, temp)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun copyToTempFile(context: Context, uri: Uri, temp: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open $uri")
        input.use { stream ->
            temp.outputStream().use { output ->
                val buffered = java.io.BufferedInputStream(stream)
                val source = if (isGzip(buffered)) GZIPInputStream(buffered) else buffered
                source.copyTo(output)
            }
        }
    }

    private fun isGzip(input: InputStream): Boolean {
        input.mark(2)
        val first = input.read()
        val second = input.read()
        input.reset()
        return first == 0x1f && second == 0x8b
    }

    private fun mergeFile(context: Context, temp: File): Result {
        val source = try {
            SQLiteDatabase.openDatabase(temp.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (exception: SQLiteException) {
            throw IllegalArgumentException("Not a UsageDirectPlus database", exception)
        }

        source.use { database ->
            val hasApps = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'apps'",
                null
            ).use { cursor -> cursor.moveToFirst() }
            if (!hasApps) {
                throw IllegalArgumentException("Not a UsageDirectPlus database: no apps table")
            }
        }

        val target = HistoryDatabase.get(context).openHelper.writableDatabase
        target.execSQL("ATTACH DATABASE ? AS src", arrayOf(temp.path))
        try {
            return mergeAttached(target)
        } finally {
            target.execSQL("DETACH DATABASE src")
        }
    }

    private fun SupportSQLiteDatabase.hasSourceTable(table: String): Boolean = query(
        "SELECT name FROM src.sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(table)
    ).use { cursor -> cursor.moveToFirst() }

    private fun mergeAttached(target: SupportSQLiteDatabase): Result {
        target.beginTransaction()
        try {
            // Register every app from the source so foreign keys can be remapped.
            target.execSQL(
                "INSERT OR IGNORE INTO main.apps (applicationId) " +
                    "SELECT applicationId FROM src.apps"
            )
            val apps = target.countChanges()

            val usageStats = if (target.hasSourceTable("usageStats")) mergeUsageStats(target) else 0
            val usageIntervals =
                if (target.hasSourceTable("usageIntervals")) mergeUsageIntervals(target) else 0
            val lastUsed = if (target.hasSourceTable("lastUsed")) mergeLastUsed(target) else 0
            val colors = if (target.hasSourceTable("colors")) mergeColors(target) else 0
            val screenEvents =
                if (target.hasSourceTable("screenEvents")) mergeScreenEvents(target) else 0
            val browserTabSessions = mergeBrowserTabSessions(target)
            val notes = if (target.hasSourceTable("notes")) mergeNotes(target) else 0

            Log.d(
                TAG,
                "Import merged $usageStats usage stats, $usageIntervals intervals, " +
                    "$lastUsed last used, $colors colors, $screenEvents screen events, " +
                    "$browserTabSessions browser sessions, $notes notes"
            )

            target.setTransactionSuccessful()
            return Result(
                apps = apps,
                usageStats = usageStats,
                usageIntervals = usageIntervals,
                lastUsed = lastUsed,
                colors = colors,
                screenEvents = screenEvents,
                browserTabSessions = browserTabSessions,
                notes = notes
            )
        } finally {
            target.endTransaction()
        }
    }

    private fun mergeUsageStats(target: SupportSQLiteDatabase): Int = insertOrIgnore(
        target,
        "INSERT OR IGNORE INTO main.usageStats (day, timeUsed, appId, hidden) " +
            "SELECT s.day, s.timeUsed, m.id, s.hidden " +
            "FROM src.usageStats s " +
            "JOIN src.apps sa ON sa.id = s.appId " +
            "JOIN main.apps m ON m.applicationId = sa.applicationId"
    )

    private fun mergeUsageIntervals(target: SupportSQLiteDatabase): Int = insertOrIgnore(
        target,
        "INSERT OR IGNORE INTO main.usageIntervals (beginTime, endTime, appId) " +
            "SELECT s.beginTime, s.endTime, m.id " +
            "FROM src.usageIntervals s " +
            "JOIN src.apps sa ON sa.id = s.appId " +
            "JOIN main.apps m ON m.applicationId = sa.applicationId"
    )

    private fun mergeLastUsed(target: SupportSQLiteDatabase): Int = insertOrIgnore(
        target,
        "INSERT OR IGNORE INTO main.lastUsed (appId, lastUsed) " +
            "SELECT m.id, s.lastUsed " +
            "FROM src.lastUsed s " +
            "JOIN src.apps sa ON sa.id = s.appId " +
            "JOIN main.apps m ON m.applicationId = sa.applicationId"
    )

    private fun mergeColors(target: SupportSQLiteDatabase): Int = insertOrIgnore(
        target,
        "INSERT OR IGNORE INTO main.colors (appId, color, priority) " +
            "SELECT m.id, s.color, s.priority " +
            "FROM src.colors s " +
            "JOIN src.apps sa ON sa.id = s.appId " +
            "JOIN main.apps m ON m.applicationId = sa.applicationId"
    )

    private fun mergeScreenEvents(target: SupportSQLiteDatabase): Int = insertOrIgnore(
        target,
        "INSERT OR IGNORE INTO main.screenEvents (timestamp, eventType) " +
            "SELECT timestamp, eventType FROM src.screenEvents"
    )

    private fun mergeBrowserTabSessions(target: SupportSQLiteDatabase): Int {
        if (!target.hasSourceTable("browserTabSessions") ||
            !target.hasSourceTable("hostnames")
        ) return 0

        target.execSQL(
            "INSERT OR IGNORE INTO main.hostnames (hostname) " +
                "SELECT hostname FROM src.hostnames"
        )

        return insertOrIgnore(
            target,
            "INSERT OR IGNORE INTO main.browserTabSessions " +
                "(appId, hostnameId, openedAt, closedAt, privacyMode, urlConfidence, closeReason) " +
                "SELECT m.id, mh.id, s.openedAt, s.closedAt, s.privacyMode, s.urlConfidence, s.closeReason " +
                "FROM src.browserTabSessions s " +
                "JOIN src.apps sa ON sa.id = s.appId " +
                "JOIN main.apps m ON m.applicationId = sa.applicationId " +
                "JOIN src.hostnames sh ON sh.id = s.hostnameId " +
                "JOIN main.hostnames mh ON mh.hostname = sh.hostname"
        )
    }

    private fun mergeNotes(target: SupportSQLiteDatabase): Int {
        val withApp = insertOrIgnore(
            target,
            "INSERT OR IGNORE INTO main.notes (appId, day, createdAt, updatedAt, tag, body) " +
                "SELECT m.id, s.day, s.createdAt, s.updatedAt, s.tag, s.body " +
                "FROM src.notes s " +
                "JOIN src.apps sa ON sa.id = s.appId " +
                "JOIN main.apps m ON m.applicationId = sa.applicationId"
        )
        val withoutApp = insertOrIgnore(
            target,
            "INSERT OR IGNORE INTO main.notes (appId, day, createdAt, updatedAt, tag, body) " +
                "SELECT NULL, s.day, s.createdAt, s.updatedAt, s.tag, s.body " +
                "FROM src.notes s WHERE s.appId IS NULL"
        )
        return withApp + withoutApp
    }

    private fun insertOrIgnore(target: SupportSQLiteDatabase, statement: String): Int {
        target.execSQL(statement)
        return target.countChanges()
    }

    private fun SupportSQLiteDatabase.countChanges(): Int = query("SELECT changes()").use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }
}
