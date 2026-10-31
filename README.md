# پویش زمین

فرانت‌اند فارسی، راست‌چین و واکنش‌گرا برای ثبت پسماند خشک، مشارکت اجتماعی و نصب به‌صورت PWA روی Android و iOS؛ به‌همراه API، D1 و اپلیکیشن میدانی Android.

## امکانات
- رابط روشن و تیره با انتخاب پایدار کاربر و پشتیبانی آفلاین PWA
- داشبورد وزن پسماند، ثبت پسماند، سوابق محلی و پیام‌های اجتماعی
- API حساب، پروفایل، سوابق و پیام‌ها با نشست امن یا Bearer token
- اپ Android برای ثبت GPS، پیوست تصویر، جست‌وجوی تصاویر OpenAerialMap و صف آفلاین با WorkManager
- پنل خصوصی شرکت در `/company.html` برای مرور سوابق میدانی

## میزبانی و API
- Cloudflare Pages پروژه را از پوشهٔ `public/` منتشر می‌کند.
- مسیر `functions/api/[[path]].js` درخواست‌های `/api/*` را به `src/index.js` می‌فرستد؛ D1 binding در تنظیمات Preview و Production پروژهٔ Pages قرار دارد.
- اپ Android برای اتصال به API باید از نشانی Pages مانند `https://pooyesh-zamin.pages.dev` استفاده کند؛ این همان مبدأیی است که `/api/*` را ارائه می‌کند.
- `wrangler.jsonc` تنظیمات استقرار مستقل Worker را نگه می‌دارد؛ R2 binding این فایل برای استقرار مستقل Worker است. برای Pages Functions، پس از ساخت bucket باید binding `MEDIA` را جداگانه در تنظیمات Functions پروژهٔ Pages برای Preview و Production ثبت کرد.
- GitHub Pages فقط نسخهٔ استاتیک را ارائه می‌کند؛ API هم‌مبدأ در آن میزبان وجود ندارد و داده‌های محلی مهمان در همان مرورگر می‌مانند.
- CORS فقط مبدأهای مشخص‌شده در Worker را می‌پذیرد؛ این محدودیت را برای دامنه‌های ناشناخته باز نکنید.

## D1 و migrationها
- `schema.sql` ساختار کامل پایگاه برای ایجاد دیتابیس تازه را توصیف می‌کند.
- migrationهای `0001` تا `0004` به‌ترتیب جدول‌های اصلی، سوابق مکانی، ارتفاع تصویربرداری و ستون‌های پروفایل را ایجاد/تکمیل می‌کنند.
- بررسی خواندنی پایگاه متصل در 2026-10-31 نشان داد `location_records` و ستون `capture_height_m` موجودند، اما ستون‌های پروفایل `users` وجود ندارند و جدول تاریخچهٔ migration نیز دیده نشد.
- روی پایگاه فعلی، migrationهای 0001 تا 0003 یا `schema.sql` را دوباره اجرا نکنید. پس از تهیهٔ نسخهٔ پشتیبان، با `PRAGMA table_info(users)` نبودن ستون‌های پروفایل را دوباره تأیید کنید؛ سپس فقط migration 0004 را یک‌بار اجرا کنید:
  ```sh
  npx wrangler d1 execute pooyesh-zamin-db --remote --file=./migrations/0004_user_profile.sql
  ```
- برای دیتابیس تازه، از `schema.sql` یا زنجیرهٔ migrationها استفاده کنید؛ هر دو را پشت سر هم اجرا نکنید.

## Android
پروژه در پوشهٔ `android/` قرار دارد. ساخت APK آزمایشی:
```sh
gradle -p android assembleDebug
```
ثبت GPS فقط پس از اقدام کاربر و اعطای مجوز انجام می‌شود؛ ارتفاع GPS گوشی معادل ارتفاع دوربین نیست.

## R2 و دسترسی شرکت — پیش‌نیاز انتشار
- Pages Functions به binding با نام `MEDIA` و bucket خصوصی `pooyesh-zamin-media` نیاز دارد.
- R2 در حساب متصل هنوز فعال نیست؛ تا فعال‌سازی R2، ساخت bucket و افزودن binding در تنظیمات Pages Preview و Production، آپلود تصاویر در دسترس نخواهد بود.
- secret با نام `COMPANY_ACCESS_TOKEN` باید در محیط Pages Functions ثبت شود؛ برای Pages از دستور `npx wrangler pages secret put COMPANY_ACCESS_TOKEN --project-name pooyesh-zamin` استفاده کنید.
- مقدار secret را در کد، مخزن یا گفتگو قرار ندهید.

## اعتبارسنجی و انتشار
GitHub Actions، syntax Worker، هر دو رابط PWA، تنظیمات Wrangler، اجرای ترتیبی migrationها روی SQLite موقت و ساخت APK آزمایشی را بررسی می‌کند. هیچ migration عملیاتی، فعال‌سازی R2، ساخت secret یا deploy در این مرحله انجام نشده است.
