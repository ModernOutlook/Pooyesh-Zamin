package ir.pooyeshzamin.fieldapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val database by lazy { FieldDatabase(this) }
    private val auth by lazy { AuthStore(this) }
    private val http by lazy { OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build() }

    private var signedIn by mutableStateOf(false)
    private var locationText by mutableStateOf("هنوز موقعیتی ثبت نشده است.")
    private var statusText by mutableStateOf("برای شروع، وارد حساب کاربری شوید.")
    private var currentLocation by mutableStateOf<Location?>(null)
    private var activeRecordId: String? = null
    private var pendingImageKind = "drone_or_camera"
    private var attachedImageText by mutableStateOf("هنوز تصویری پیوست نشده است.")
    private var captureHeightInput by mutableStateOf("")
    private var aerialImages by mutableStateOf<List<AerialImage>>(emptyList())
    private var imageryStatus by mutableStateOf("برای جست‌وجوی تصاویر هوایی، ابتدا موقعیت را ثبت کنید.")
    private var busy by mutableStateOf(false)

    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) acquireLocation()
        else statusText = "دسترسی مکان‌یابی داده نشد."
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) savePickedImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        signedIn = try { !auth.token.isNullOrBlank() && auth.baseUrl.startsWith("https://") } catch (_: Exception) { false }
        if (signedIn) scheduleSync()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (!signedIn) {
                        LoginPanel(
                            initialUrl = runCatching { auth.baseUrl }.getOrDefault(""),
                            status = statusText,
                            busy = busy,
                            onAuthenticate = { url, phone, password, name, accountType, register -> authenticate(url, phone, password, name, accountType, register) }
                        )
                    } else {
                        FieldPanel(
                            locationText = locationText,
                            status = statusText,
                            imageryStatus = imageryStatus,
                            images = aerialImages,
                            attachedImageText = attachedImageText,
                            captureHeight = captureHeightInput,
                            onCaptureHeightChange = { value ->
                                val cleaned = value.filter { ch -> ch.isDigit() || ch == '.' }
                                captureHeightInput = cleaned
                                activeRecordId?.let { id ->
                                    val height = cleaned.toDoubleOrNull()?.takeIf { it in 0.0..500.0 }
                                    database.updateCaptureHeight(id, height)
                                    scheduleSync()
                                }
                            },
                            busy = busy,
                            onLocate = { requestLocation() },
                            onPickImage = {
                                if (activeRecordId == null) statusText = "ابتدا موقعیت را ثبت کنید."
                                else { pendingImageKind = "drone_or_camera"; imagePicker.launch("image/*") }
                            },
                            onSearchImagery = { searchImagery() },
                            onDownloadImage = { item -> downloadAerialImage(item) },
                            onOpenMap = { openMap() },
                            onLogout = { logout() },
                            pendingCount = database.pendingCount()
                        )
                    }
                }
            }
        }
    }

    private fun authenticate(urlInput: String, phone: String, password: String, name: String, accountType: String, register: Boolean) {
        val base = urlInput.trim().trimEnd('/')
        if (!base.startsWith("https://") || base.contains("YOUR-SUBDOMAIN")) {
            statusText = "نشانی HTTPS سرور Worker را کامل وارد کنید."
            return
        }
        if (phone.isBlank() || password.isBlank()) { statusText = "شماره همراه و گذرواژه را وارد کنید."; return }
        busy = true
        Thread {
            try {
                val body = JSONObject().put("phone", phone.trim()).put("password", password).put("name", name.trim()).put("accountType", accountType)
                    .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val endpoint = if (register) "register" else "login"
                val request = Request.Builder().url("$base/api/auth/$endpoint").post(body).build()
                http.newCall(request).execute().use { response ->
                    val payload = JSONObject(response.body?.string().orEmpty())
                    if (!response.isSuccessful) throw IllegalStateException(payload.optString("error", "ورود ناموفق بود."))
                    val token = payload.optString("access_token")
                    if (token.isBlank()) throw IllegalStateException("سرور توکن نشست را برنگرداند.")
                    auth.baseUrl = base
                    auth.token = token
                    runOnUiThread { signedIn = true; busy = false; statusText = "ورود موفق بود. اکنون می‌توانید موقعیت ثبت کنید."; scheduleSync() }
                }
            } catch (error: Exception) {
                runOnUiThread { busy = false; statusText = error.message ?: "ارتباط با سرور برقرار نشد." }
            }
        }.start()
    }

    private fun logout() {
        val token = auth.token
        if (!token.isNullOrBlank() && auth.baseUrl.startsWith("https://")) {
            Thread {
                runCatching {
                    val request = Request.Builder().url("${auth.baseUrl}/api/auth/logout")
                        .header("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody()).build()
                    http.newCall(request).execute().close()
                }
            }.start()
        }
        auth.clear()
        signedIn = false
        statusText = "از حساب خارج شدید. اطلاعات ذخیره‌شده محلی باقی می‌مانند."
    }

    private fun requestLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        else acquireLocation()
    }

    private fun acquireLocation() {
        busy = true
        statusText = "در حال دریافت مختصات دقیق…"
        val cancellation = CancellationTokenSource()
        try {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                .addOnSuccessListener { location ->
                    if (location == null) {
                        busy = false
                        statusText = "موقعیت دریافت نشد؛ GPS را روشن کنید و در فضای باز دوباره تلاش کنید."
                        return@addOnSuccessListener
                    }
                    saveOffline(location)
                }
                .addOnFailureListener {
                    busy = false
                    statusText = "خطا در مکان‌یابی: ${it.localizedMessage ?: "نامشخص"}"
                }
        } catch (_: SecurityException) {
            busy = false
            statusText = "مجوز مکان‌یابی در دسترس نیست."
        }
    }

    private fun saveOffline(location: Location) {
        val id = UUID.randomUUID().toString()
        val record = PendingRecord(
            clientId = id,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy.takeIf { location.hasAccuracy() },
            altitude = location.altitude.takeIf { location.hasAltitude() },
            captureHeight = captureHeightInput.toDoubleOrNull()?.takeIf { it in 0.0..500.0 },
            capturedAt = Instant.now().toString(),
            imagePath = null,
            imageMime = null,
            imageKind = "none",
            imageSource = "none",
            notes = ""
        )
        database.insert(record)
        activeRecordId = id
        currentLocation = location
        locationText = "عرض جغرافیایی: ${location.latitude}\nطول جغرافیایی: ${location.longitude}\nدقت افقی GPS: ${if (location.hasAccuracy()) "${location.accuracy} متر" else "نامشخص"}\nارتفاع GPS گوشی: ${if (location.hasAltitude()) "${location.altitude} متر" else "نامشخص"}"
        statusText = "موقعیت ابتدا در حافظه گوشی ذخیره شد. موارد منتظر ارسال: ${database.pendingCount()}"
        busy = false
        scheduleSync()
        searchImagery()
    }

    private fun searchImagery() {
        val location = currentLocation ?: run { imageryStatus = "ابتدا موقعیت مکانی را ثبت کنید."; return }
        val token = auth.token ?: return
        imageryStatus = "در حال جست‌وجو در کاتالوگ تصاویر هوایی آزاد…"
        Thread {
            try {
                val results = ImageryRepository().search(auth.baseUrl, token, location.latitude, location.longitude)
                runOnUiThread {
                    aerialImages = results
                    imageryStatus = if (results.isEmpty()) "برای این محدوده تصویری در کاتالوگ پیدا نشد. می‌توانید تصویر پهپاد یا دوربین را از گوشی پیوست کنید."
                    else "${results.size} تصویر/داده هوایی پیدا شد. تاریخ و مجوز هر مورد را پیش از استفاده بررسی کنید."
                }
            } catch (error: Exception) {
                runOnUiThread { imageryStatus = "جست‌وجوی تصاویر انجام نشد: ${error.message ?: "خطای شبکه"}" }
            }
        }.start()
    }

    private fun downloadAerialImage(item: AerialImage) {
        val recordId = activeRecordId ?: run { statusText = "ابتدا موقعیت را ثبت کنید."; return }
        if (!item.downloadUrl.startsWith("https://")) { imageryStatus = "نشانی منبع تصویر امن نیست."; return }
        busy = true
        imageryStatus = "در حال دریافت فایل تصویر از کاتالوگ…"
        Thread {
            try {
                val request = Request.Builder().url(item.downloadUrl).get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("دریافت فایل ناموفق بود.")
                    val mime = response.header("Content-Type")?.substringBefore(";")?.lowercase() ?: "application/octet-stream"
                    if (mime !in setOf("image/jpeg", "image/png", "image/webp", "image/tiff", "application/geotiff", "image/heic")) {
                        throw IllegalStateException("قالب فایل منبع قابل ذخیره به‌عنوان تصویر نیست: $mime")
                    }
                    val body = response.body ?: throw IllegalStateException("فایل خالی است.")
                    if (body.contentLength() > 50L * 1024 * 1024) throw IllegalStateException("حجم فایل از ۵۰ مگابایت بیشتر است.")
                    val folder = File(filesDir, "field-images").apply { mkdirs() }
                    val extension = when (mime) {
                        "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"
                        "image/heic" -> "heic"; else -> "tif"
                    }
                    val file = File(folder, "$recordId.$extension")
                    body.byteStream().use { input -> file.outputStream().use { output ->
                        val buffer = ByteArray(8192); var total = 0L
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            total += count
                            if (total > 50L * 1024 * 1024) throw IllegalStateException("حجم فایل از ۵۰ مگابایت بیشتر است.")
                            output.write(buffer, 0, count)
                        }
                    } }
                    database.attachImage(recordId, file.absolutePath, mime, "aerial_catalog", "OpenAerialMap")
                    runOnUiThread {
                        attachedImageText = "تصویر کاتالوگ ذخیره شد: ${item.title} • ${item.date} • ${item.license}"
                        imageryStatus = "فایل روی گوشی ذخیره شد و در صف ارسال به سرور قرار گرفت."
                        busy = false
                        scheduleSync()
                    }
                }
            } catch (error: Exception) {
                runOnUiThread { busy = false; imageryStatus = error.message ?: "دریافت تصویر ناموفق بود." }
            }
        }.start()
    }

    private fun savePickedImage(uri: Uri) {
        val recordId = activeRecordId ?: return
        busy = true
        Thread {
            try {
                val mime = contentResolver.getType(uri) ?: "image/jpeg"
                if (mime !in setOf("image/jpeg", "image/png", "image/webp", "image/heic")) throw IllegalStateException("فقط تصاویر JPEG، PNG، WebP و HEIC پشتیبانی می‌شوند.")
                val folder = File(filesDir, "field-images").apply { mkdirs() }
                val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; "image/heic" -> "heic"; else -> "jpg" }
                val file = File(folder, "$recordId-user.$ext")
                contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(8192); var total = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count
                        if (total > 50L * 1024 * 1024) throw IllegalStateException("حجم تصویر از ۵۰ مگابایت بیشتر است.")
                        output.write(buffer, 0, count)
                    }
                } } ?: throw IllegalStateException("خواندن تصویر ممکن نشد.")
                database.attachImage(recordId, file.absolutePath, mime, "drone_or_camera", "user_selected_file")
                runOnUiThread {
                    attachedImageText = "تصویر انتخاب‌شده از گوشی ذخیره شد."
                    statusText = "تصویر به پرونده محلی پیوست شد و منتظر ارسال است."
                    busy = false
                    scheduleSync()
                }
            } catch (error: Exception) {
                runOnUiThread { busy = false; statusText = error.message ?: "ذخیره تصویر ناموفق بود." }
            }
        }.start()
    }

    private fun openMap() {
        val location = currentLocation ?: return
        val uri = Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun scheduleSync() {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("pooyesh-location-sync", ExistingWorkPolicy.KEEP, request)
    }
}

@Composable
private fun LoginPanel(
    initialUrl: String,
    status: String,
    busy: Boolean,
    onAuthenticate: (String, String, String, String, String, Boolean) -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var accountType by remember { mutableStateOf("home") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("پویش زمین", style = MaterialTheme.typography.headlineLarge)
        Text("سامانه ثبت موقعیت و مستندات میدانی", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("نشانی سرور HTTPS") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("شماره همراه") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("گذرواژه") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = fullName, onValueChange = { fullName = it }, label = { Text("نام و نام خانوادگی (برای ثبت‌نام)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("نوع حساب")
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = accountType == "home", onClick = { accountType = "home" }); Text("خانوار") }
            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = accountType == "collector", onClick = { accountType = "collector" }); Text("جمع‌آور") }
        }
        Button(onClick = { onAuthenticate(url, phone, password, fullName, accountType, false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("ورود") }
        OutlinedButton(onClick = { onAuthenticate(url, phone, password, fullName, accountType, true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("ایجاد حساب") }
        if (busy) CircularProgressIndicator()
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Text("برای ثبت و ارسال سوابق، اینترنت لازم است؛ ثبت‌های بعدی در حالت آفلاین روی گوشی نگه‌داری می‌شوند.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FieldPanel(
    locationText: String,
    status: String,
    imageryStatus: String,
    images: List<AerialImage>,
    attachedImageText: String,
    captureHeight: String,
    onCaptureHeightChange: (String) -> Unit,
    busy: Boolean,
    onLocate: () -> Unit,
    onPickImage: () -> Unit,
    onSearchImagery: () -> Unit,
    onDownloadImage: (AerialImage) -> Unit,
    onOpenMap: () -> Unit,
    onLogout: () -> Unit,
    pendingCount: Int
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("پویش زمین", style = MaterialTheme.typography.headlineLarge)
        Text("ثبت موقعیت و مستندات تصویری", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onLocate, enabled = !busy, modifier = Modifier.fillMaxWidth().height(58.dp)) {
            Text("دریافت موقعیت مکانی")
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("اطلاعات موقعیت", style = MaterialTheme.typography.titleMedium)
                Text(locationText)
                OutlinedButton(onClick = onOpenMap, modifier = Modifier.fillMaxWidth()) { Text("نمایش موقعیت روی نقشه") }
            }
        }
        Text("تصاویر هوایی با مجوز باز", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onSearchImagery, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("جست‌وجوی تصویر هوایی این محدوده")
        }
        Text(imageryStatus, style = MaterialTheme.typography.bodySmall)
        images.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                    Text("تاریخ: ${item.date.ifBlank { "ثبت نشده" }}")
                    Text("مجوز: ${item.license}")
                    Text("شناسه منبع: ${item.id}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onDownloadImage(item) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("دریافت و پیوست این تصویر")
                    }
                }
            }
        }
        Divider()
        Text("تصویر واقعی دوربین یا پهپاد", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = captureHeight, onValueChange = onCaptureHeightChange, label = { Text("ارتفاع تصویربرداری اعلام‌شده (متر)") }, supportingText = { Text("مثلاً ۱۰؛ مقدار دستی است و باید با داده پرواز راستی‌آزمایی شود.") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedButton(onClick = onPickImage, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("انتخاب عکس از گوشی / پهپاد")
        }
        Text(attachedImageText, style = MaterialTheme.typography.bodySmall)
        Text("ارتفاع GPS گوشی، ارتفاع دوربین نیست. برای تصویر واقعی ۱۰ متری، فایل پهپاد و اطلاعات پرواز معتبر لازم است؛ ارتفاع ادعاشده باید جداگانه راستی‌آزمایی شود.", style = MaterialTheme.typography.bodySmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("همگام‌سازی", style = MaterialTheme.typography.titleMedium)
                Text("موارد منتظر ارسال: $pendingCount")
                Text(status, style = MaterialTheme.typography.bodySmall)
                Text("ثبت محلی بدون اینترنت انجام می‌شود؛ ارسال خودکار پس از اتصال شبکه و ورود معتبر انجام خواهد شد.", style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("خروج از حساب") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
