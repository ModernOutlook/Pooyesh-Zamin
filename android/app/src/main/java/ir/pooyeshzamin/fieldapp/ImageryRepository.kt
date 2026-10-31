package ir.pooyeshzamin.fieldapp

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class AerialImage(val id: String, val title: String, val date: String, val license: String, val downloadUrl: String)

class ImageryRepository {
    private val client = OkHttpClient.Builder().callTimeout(35, TimeUnit.SECONDS).build()

    fun search(baseUrl: String, token: String, lat: Double, lon: Double): List<AerialImage> {
        val delta = 0.0008
        val bbox = "${lon-delta},${lat-delta},${lon+delta},${lat+delta}"
        val url = "$baseUrl/api/imagery/search?bbox=" + URLEncoder.encode(bbox, "UTF-8")
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val features = JSONObject(response.body?.string().orEmpty()).optJSONArray("features") ?: return emptyList()
            return (0 until features.length()).mapNotNull { index ->
                val feature = features.optJSONObject(index) ?: return@mapNotNull null
                val props = feature.optJSONObject("properties") ?: JSONObject()
                val assets = feature.optJSONObject("assets") ?: JSONObject()
                val visual = assets.optJSONObject("visual") ?: assets.optJSONObject("data") ?: return@mapNotNull null
                val href = visual.optString("href").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
                AerialImage(feature.optString("id"), props.optString("title", "تصویر هوایی"), props.optString("datetime"), props.optString("license", "مجوز در فراداده منبع بررسی شود"), href)
            }
        }
    }
}
