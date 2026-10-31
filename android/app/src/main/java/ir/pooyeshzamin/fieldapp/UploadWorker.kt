package ir.pooyeshzamin.fieldapp

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val auth = try { AuthStore(applicationContext) } catch (_: Exception) { return Result.failure() }
        val token = auth.token ?: return Result.failure()
        val base = auth.baseUrl
        if (!base.startsWith("https://")) return Result.failure()
        val db = FieldDatabase(applicationContext)
        val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
        for (record in db.pending()) {
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("client_id", record.clientId)
                .addFormDataPart("latitude", record.latitude.toString())
                .addFormDataPart("longitude", record.longitude.toString())
                .addFormDataPart("accuracy_m", record.accuracy?.toString() ?: "")
                .addFormDataPart("altitude_m", record.altitude?.toString() ?: "")
                .addFormDataPart("capture_height_m", record.captureHeight?.toString() ?: "")
                .addFormDataPart("captured_at", record.capturedAt)
                .addFormDataPart("image_kind", record.imageKind)
                .addFormDataPart("image_source", record.imageSource)
                .addFormDataPart("notes", record.notes)
            record.imagePath?.let { path ->
                val file = File(path)
                if (file.isFile) form.addFormDataPart("image", file.name, file.asRequestBody((record.imageMime ?: "image/jpeg").toMediaTypeOrNull()))
            }
            val request = Request.Builder().url("$base/api/location-records")
                .header("Authorization", "Bearer $token").post(form.build()).build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 401) return Result.failure()
                    if (!response.isSuccessful) return Result.retry()
                    db.markSynced(record.clientId)
                }
            } catch (_: Exception) { return Result.retry() }
        }
        return if (db.pendingCount() == 0) Result.success() else Result.retry()
    }
}
