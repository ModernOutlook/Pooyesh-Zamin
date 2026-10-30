# پویش زمین

فرانت‌اند فارسی، راست‌چین و واکنش‌گرا برای ثبت پسماند خشک، مشارکت اجتماعی و نصب به‌صورت PWA روی Android و iOS.

## امکانات
- داشبورد وزن پسماند، تعداد مشارکت‌ها و تنوع دسته‌ها
- ثبت پسماند کاغذ، پلاستیک، فلز، شیشه، الکترونیکی، منسوجات و سایر
- سوابق محلی با پشتیبانی پایه آفلاین
- صفحه پیام‌های اجتماعی
- طراحی واکنش‌گرا و نصب به‌صورت PWA
- اتصال به API Worker برای ورود، ثبت‌نام، سوابق و پیام‌ها در حالت same-origin

## انتشار روی GitHub Pages
Workflow در `.github/workflows/deploy-pages.yml` پوشه `public/` را با هر push به `main` منتشر می‌کند.

1. در GitHub به Settings → Pages بروید.
2. گزینه Build and deployment → Source را روی GitHub Actions قرار دهید.
3. پس از push، اجرای Workflow را در زبانه Actions بررسی کنید.
4. آدرس Pages معمولاً `https://modernoutlook.github.io/Pooyesh-Zamin/` است.

در Android از منوی مرورگر Install app / Add to Home Screen را بزنید. در iPhone/iPad از Safari، Share و سپس Add to Home Screen را انتخاب کنید.

## API و ذخیره‌سازی
- وقتی فرانت‌اند از همان Worker ارائه شود، API مسیرهای `/api/*` و نشست امن HttpOnly هم‌مبدأ استفاده می‌شوند.
- در GitHub Pages، ثبت‌ها و پیام‌های مهمان فقط در localStorage همان مرورگر/دستگاه ذخیره می‌شوند. همگام‌سازی حساب از مبدأ GitHub Pages عمداً فعال نشده است تا کوکی‌های نشست ناامن cross-site استفاده نشوند.
- برای حساب‌های مشترک و داده‌های همگام‌شده، Worker را مستقر کنید تا فرانت‌اند و API از یک مبدأ باشند.

## راه‌اندازی Worker و D1
```bash
npm install
npx wrangler login
npx wrangler d1 execute pooyesh-zamin-db --remote --file=./schema.sql
npx wrangler deploy
```
بررسی API از مسیر `/api/health` انجام می‌شود.

## محدودیت‌ها
- پیام و رکورد مهمان در GitHub Pages بین دستگاه‌ها همگام نمی‌شود.
- API فعلی بارگذاری تصویر و رزرو زمان جمع‌آوری را پیاده‌سازی نکرده است.
- پیش از استفاده عمومی، rate limiting، تأیید شماره همراه، بازیابی گذرواژه، سیاست حریم خصوصی و آزمون امنیتی لازم است.
