# البناء والتثبيت

لا تحتاج تعديل أي كود. تحتاج فقط عنوان موقعك (HTTPS) مثل `https://example.com`. لا تضع كلمات مرور ولا مفاتيح في أي ملف.

## 1) Shadow Admin (ويندوز → EXE)

**المتطلبات:** Windows 10/11 64-بت، [Node.js 18+](https://nodejs.org)، اتصال بالإنترنت للبناء.

```bat
cd Source_Code\Admin
npm install
npm run dist
```

الناتج في `Source_Code\Admin\dist\`:
- `ShadowAdmin-1.0.0-nsis.exe` مثبّت عادي.
- `ShadowAdmin-1.0.0-portable.exe` نسخة محمولة بلا تثبيت.

**عنوان الخادم:** إما أن تكتبه في `Source_Code/Admin/config.json` قبل البناء (`"serverUrl": "https://example.com"`)، أو تتركه فارغًا فتظهر شاشة إعداد عند أول تشغيل تحفظه على جهازك. للتجربة قبل البناء: `npm start`.

**ملاحظات:**
- التطبيق غير موقَّع رقميًا، لذلك قد يعرض ويندوز تحذير SmartScreen؛ هذا متوقع لأي تطبيق غير موقَّع.
- بعد أول تشغيل اتبع خطوة «ربط جهاز الأدمن» في `CONNECTION_AR.md`، وإلا سيرفض الخادم الدخول.

## 2) Shadow Store (أندرويد → APK)

**المتطلبات:** [Android Studio](https://developer.android.com/studio) (يثبّت JDK وSDK 34 تلقائيًا).

1. افتح المجلد `Source_Code/Android` في Android Studio.
2. عدّل السطر في `gradle.properties`:
   ```
   SHADOW_SERVER_URL=https://example.com
   ```
   (عنوان عام وليس سرًّا. البناء يتوقف برسالة واضحة إن تُرك فارغًا أو لم يبدأ بـ https.)
3. انتظر مزامنة Gradle، ثم: **Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)**.
4. الملف الناتج: `app/build/outputs/apk/debug/app-debug.apk`.

للنشر العلني أنشئ نسخة موقّعة: **Build ▸ Generate Signed Bundle / APK** واحتفظ بمفتاح التوقيع بنفسك (لا تضعه في المشروع).

**الحد الأدنى:** Android 7.0 (API 24). هذا الحد مضبوط في الإعدادات ولم يُختبر على أجهزة فعلية.

**ملاحظة:** ملف `gradle-wrapper.jar` غير مرفق (ملف ثنائي لا أستطيع إنشاءه هنا). Android Studio يتكفّل بذلك؛ ولتشغيل `gradlew` من الطرفية نفّذ مرة واحدة `gradle wrapper`.
