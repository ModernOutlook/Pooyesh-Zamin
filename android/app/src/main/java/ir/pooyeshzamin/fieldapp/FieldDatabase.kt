package ir.pooyeshzamin.fieldapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class PendingRecord(
    val clientId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val altitude: Double?,
    val captureHeight: Double?,
    val capturedAt: String,
    val imagePath: String?,
    val imageMime: String?,
    val imageKind: String,
    val imageSource: String,
    val notes: String
)

class FieldDatabase(context: Context) : SQLiteOpenHelper(context, "field_records.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE records (
            client_id TEXT PRIMARY KEY,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL,
            accuracy_m REAL,
            altitude_m REAL,
            capture_height_m REAL,
            captured_at TEXT NOT NULL,
            image_path TEXT,
            image_mime TEXT,
            image_kind TEXT NOT NULL,
            image_source TEXT NOT NULL,
            notes TEXT NOT NULL DEFAULT '',
            synced INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE records ADD COLUMN capture_height_m REAL")
    }

    @Synchronized
    fun insert(record: PendingRecord) {
        val values = ContentValues().apply {
            put("client_id", record.clientId)
            put("latitude", record.latitude)
            put("longitude", record.longitude)
            record.accuracy?.let { put("accuracy_m", it) }
            record.altitude?.let { put("altitude_m", it) }
            record.captureHeight?.let { put("capture_height_m", it) }
            put("captured_at", record.capturedAt)
            put("image_path", record.imagePath)
            put("image_mime", record.imageMime)
            put("image_kind", record.imageKind)
            put("image_source", record.imageSource)
            put("notes", record.notes)
        }
        writableDatabase.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun pending(limit: Int = 30): List<PendingRecord> {
        val output = mutableListOf<PendingRecord>()
        readableDatabase.rawQuery(
            "SELECT * FROM records WHERE synced=0 ORDER BY created_at LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                fun nullableDouble(column: String): Double? {
                    val index = cursor.getColumnIndexOrThrow(column)
                    return if (cursor.isNull(index)) null else cursor.getDouble(index)
                }
                fun nullableString(column: String): String? {
                    val index = cursor.getColumnIndexOrThrow(column)
                    return if (cursor.isNull(index)) null else cursor.getString(index)
                }
                output += PendingRecord(
                    clientId = cursor.getString(cursor.getColumnIndexOrThrow("client_id")),
                    latitude = cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")),
                    longitude = cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")),
                    accuracy = nullableDouble("accuracy_m")?.toFloat(),
                    altitude = nullableDouble("altitude_m"),
                    captureHeight = nullableDouble("capture_height_m"),
                    capturedAt = cursor.getString(cursor.getColumnIndexOrThrow("captured_at")),
                    imagePath = nullableString("image_path"),
                    imageMime = nullableString("image_mime"),
                    imageKind = cursor.getString(cursor.getColumnIndexOrThrow("image_kind")),
                    imageSource = cursor.getString(cursor.getColumnIndexOrThrow("image_source")),
                    notes = cursor.getString(cursor.getColumnIndexOrThrow("notes"))
                )
            }
        }
        return output
    }

    @Synchronized
    fun updateCaptureHeight(clientId: String, height: Double?) {
        writableDatabase.execSQL("UPDATE records SET capture_height_m=?,synced=0 WHERE client_id=?", arrayOf(height, clientId))
    }

    @Synchronized
    fun attachImage(clientId: String, path: String, mime: String, kind: String, source: String) {
        writableDatabase.execSQL(
            "UPDATE records SET image_path=?,image_mime=?,image_kind=?,image_source=?,synced=0 WHERE client_id=?",
            arrayOf(path, mime, kind, source, clientId)
        )
    }

    @Synchronized
    fun markSynced(clientId: String) {
        writableDatabase.execSQL("UPDATE records SET synced=1 WHERE client_id=?", arrayOf(clientId))
    }

    fun pendingCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM records WHERE synced=0", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
}
