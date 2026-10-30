# پویش زمین

رابط کاربری فارسی و راست‌چین به‌همراه API روی Cloudflare Workers، پایگاه داده D1 و ذخیره‌سازی خصوصی فایل‌ها در R2.

## اجزای پروژه
- API در `src/index.js`: ثبت‌نام، ورود/خروج، نشست، سوابق پسماند، مطالب و ثبت موقعیت‌های میدانی.
- طرح کامل پایگاه داده تازه در `schema.sql`.
- migrationهای افزایشی و امن در `migrations/`.
- پیکربندی Worker در `wrangler.jsonc`.
- اپ Android در `android/`.
- پنل مشاهده پرونده‌های شرکت در `public/company.html`.

## راه‌اندازی D1 موجود

پایگاه داده `pooyesh-zamin-db` با شناسه موجود در پیکربندی حساب Cloudflare پیدا شد؛ در زمان بررسی هنوز هیچ جدولی در آن ثبت نشده بود. طرح را با migrationها به ترتیب نصب کنید:

```bash
npm install
npx wrangler d1 migrations apply pooyesh-zamin-db --remote
```

از اجرای دوباره `schema.sql` روی دیتابیسی که قبلاً جدول دارد خودداری کنید. `schema.sql` فقط برای راه‌اندازی مستقیم دیتابیس کاملاً تازه است.

## راه‌اندازی R2 و Secretها

1. در Cloudflare Dashboard به **Storage & databases → R2 → Overview** بروید و در صورت درخواست، اشتراک R2 را فعال کنید.
2. پس از فعال‌شدن R2، bucket خصوصی با نام `pooyesh-zamin-media` بسازید. محتوای bucket عمومی نمی‌شود؛ دسترسی فقط از Worker و با احراز هویت انجام می‌شود.
3. Secret دسترسی پنل شرکت را تنظیم کنید:
   `npx wrangler secret put COMPANY_ACCESS_TOKEN`
4. پس از تأیید bindingهای `DB` و `MEDIA`، Worker را deploy کنید:
   `npm run deploy`

R2 در حساب متصل فعلاً فعال نیست؛ بنابراین ساخت bucket از API با خطای فعال‌سازی مواجه شد. تا فعال‌سازی R2، ثبت مختصات و صف محلی کار می‌کند اما آپلود تصویر روی سرور در دسترس نخواهد بود.

## اپلیکیشن اندرویدی میدانی

راهنمای ساخت APK، ورود، ثبت GPS، ذخیره آفلاین، همگام‌سازی و دریافت تصاویر هوایی در [راهنمای اپ اندروید](android/README.md) آمده است.

## پنل شرکت

پس از deploy، پنل در مسیر `/company.html` در دسترس است. توکن `COMPANY_ACCESS_TOKEN` فقط باید به کارکنان مجاز تحویل داده شود؛ پنل آن را در localStorage یا کوکی ذخیره نمی‌کند.

## وضعیت انتشار

کد این قابلیت در شاخه `feature/android-location-capture` و PR شماره 2 قرار دارد. ساخت APK، آزمون روی دستگاه، migration واقعی D1، فعال‌سازی R2 و deploy هنوز انجام نشده‌اند. ادغام PR نیز انجام نشده است.
