# پویش زمین

رابط کاربری فارسی و راست‌چین به‌همراه API روی Cloudflare Workers و ذخیره‌سازی D1.

## وضعیت فعلی
- API اولیه در `src/index.js`: ثبت‌نام، ورود، خروج، وضعیت نشست، سوابق پسماند و فهرست/انتشار مطالب.
- طرح پایگاه داده در `schema.sql`.
- تنظیمات Worker و Assets در `wrangler.jsonc`.
- **فایل اصلی رابط کاربری هنوز باید در `public/index.html` قرار بگیرد.**
- **استقرار زنده انجام نشده است**؛ شناسه D1 و تنظیمات دسترسی Cloudflare هنوز تأیید نشده‌اند.

## انتشار با Cloudflare Workers (رایگان با workers.dev)
1. در مخزن، فایل HTML پیوست را با نام `public/index.html` بارگذاری کنید.
2. در Cloudflare، یک D1 Database با نام `pooyesh-zamin-db` بسازید و `database_id` واقعی را در `wrangler.jsonc` وارد کنید.
3. در محیطی که Node.js دارد، از ریشه پروژه اجرا کنید:
   `npm install`
   `npx wrangler login`
   `npx wrangler d1 execute pooyesh-zamin-db --remote --file=./schema.sql`
   `npx wrangler deploy`
4. آدرس `https://pooyesh-zamin.<YOUR-SUBDOMAIN>.workers.dev` را در خروجی deploy بردارید و `/api/health` را برای بررسی API باز کنید.

## امنیت و محدودیت‌ها
- گذرواژه‌ها با PBKDF2 هش می‌شوند و شناسه نشست در کوکی HttpOnly/Secure نگه‌داری می‌شود.
- برای انتشار عمومی، محدودسازی نرخ درخواست، تأیید شماره همراه، بازیابی گذرواژه، سیاست حریم خصوصی و آزمون امنیتی لازم است.
- بارگذاری واقعی تصویر در پست‌ها هنوز پیاده‌سازی نشده و به Cloudflare R2 نیاز دارد.
- هر قابلیت فرانت‌اندی که هنوز فقط از `localStorage` استفاده می‌کند، به‌صورت خودکار به ذخیره‌سازی مشترک تبدیل نمی‌شود؛ باید اتصال آن به API به‌طور جداگانه انجام و آزمون شود.
