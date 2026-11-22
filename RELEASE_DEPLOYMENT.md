# دليل البناء والتوقيع والنشر التلقائي لتطبيق Share Flow
# Share Flow Automated Release & Direct APK Distribution Guide

---

## 1. الروابط المباشرة لأحدث إصدار (Direct Release URLs)

عند نشر الإصدار عبر `.github/workflows/release.yml` في المستودع `ow7ab/Share-Flow`، تتحدث الروابط التالية تلقائياً دون أي ملفات ZIP ودون توجيه المستخدم لصفحة ملفات GitHub:

- **رابط التحميل المباشر لملف APK (أحدث إصدار دائماً):**
  `https://github.com/ow7ab/Share-Flow/releases/latest/download/Share-Flow.apk`
- **رابط ملف التحديث السحابي للتطبيق (`version.json`):**
  `https://github.com/ow7ab/Share-Flow/releases/latest/download/version.json`
- **رابط بصمة التحقق SHA-256:**
  `https://github.com/ow7ab/Share-Flow/releases/latest/download/Share-Flow.apk.sha256`
- **موقع التحميل المباشر (GitHub Pages من مجلد `/docs`):**
  `https://ow7ab.github.io/Share-Flow/`

- **هوية التطبيق الحالية المحفوظة دون تغيير:**
  - `applicationId`: `com.aistudio.shareflow.kxrvnm`
  - `versionName`: `1.0.10` | `versionCode`: `11`

---

## 2. إعداد أسرار التوقيع في GitHub Secrets (مرة واحدة فقط)

اذهب إلى المستودع على GitHub:
**Settings → Secrets and variables → Actions → New repository secret**

أضف الأسرار التالية (لا تقم أبداً برفع ملفات `.jks` أو `.keystore` أو كلمات المرور داخل المستودع):

| اسم السر (Secret Name) | الوصف | إلزامي؟ |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | محتوى مفتاح التوقيع الدائم محولاً إلى Base64 عبر الأمر `base64 -w 0 my-upload-key.jks` | نعم |
| `ANDROID_STORE_PASSWORD` | كلمة مرور ملف Keystore | نعم |
| `ANDROID_KEY_PASSWORD` | كلمة مرور المفتاح الداخلي (Key Password) | نعم |
| `ANDROID_KEY_ALIAS` | الاسم المستعار للمفتاح داخل Keystore (الافتراضي `upload` مطابق لـ `app/build.gradle.kts`) | اختياري |
| `EXPECTED_CERT_SHA256` | بصمة SHA-256 لشهادة التوقيع المعتمدة لمنع نشر أي إصدار بشهادة مختلفة | اختياري (موصى به بشدة) |

> **حماية توافق التحديثات:** سير العمل لا يُنشئ مفتاح توقيع جديداً أبداً، ويتحقق من وجود `PrivateKeyEntry`، ويتحقق من عدم حدوث تراجع في `versionCode` قبل النشر. إذا غاب أي سر أساسي أو فشل التوقيع يتوقف البناء فوراً دون نشر نسخة غير متوافقة.

---

## 3. كيفية تشغيل النشر التلقائي وما يتحقق منه قبل النشر

يعمل سير العمل `.github/workflows/release.yml` تلقائياً في ثلاث حالات:
1. عند كل دفع (Push) جديد إلى فرع `main`.
2. عند إنشاء وسم إصدار جديد يبدأ بـ `v` مثل `v1.0.10`.
3. يدوياً من تبويب **Actions → ShareFlow Automated Release Build & APK Publish → Run workflow** (مع إمكانية كتابة ملاحظات إصدار بالعربية والإنجليزية).

قبل النشر يتحقق سير العمل تلقائياً من:
1. التحقق من وجود أسرار التوقيع وسلامة مفتاح `PrivateKeyEntry` عبر `keytool` (بقراءة كلمة المرور من متغير البيئة وليس من سطر الأوامر).
2. اجتياز اختبارات بوابة التحميل (`node --test ./scripts/test-portal.js`).
3. اجتياز اختبارات الوحدة وRobolectric (`./gradlew :app:testDebugUnitTest`).
4. نجاح بناء Release APK وAAB عبر Gradle Wrapper (`./gradlew :app:assembleRelease :app:bundleRelease`).
5. صحة التوقيع الرقمي (v1/v2/v3) عبر `apksigner verify --verbose --print-certs` ومطابقة `EXPECTED_CERT_SHA256` عند ضبطه.
6. محاذاة الملف (`zipalign -c -v 4`) لضمان التثبيت المباشر على Android 11+.
7. تطابق معرف الحزمة (`com.aistudio.shareflow.kxrvnm`) ووجود النشاط الرئيسي عبر `aapt2 dump badging`.
8. منع التراجع في رقم البناء (`versionCode`) مقارنة بآخر `version.json` منشور.
9. رفع `Share-Flow.apk` مباشرة (بدون ZIP) داخل GitHub Releases مع الاحتفاظ بكافة الإصدارات السابقة والتحقق عبر HTTP HEAD أن رابط التحميل المباشر يعيد ملفاً ثنائياً مطابق الحجم وليس صفحة HTML.
10. حذف ملف Keystore المؤقت من مجلد `RUNNER_TEMP` دائماً حتى عند فشل البناء.

---

## 4. تفعيل موقع التحميل المباشر (GitHub Pages)

1. في إعدادات المستودع: **Settings → Pages**.
2. اختر **Source: Deploy from a branch**.
3. اختر الفرع **`main`** والمجلد **`/docs`** ثم اضغط **Save**.
4. سيعرض الموقع فوراً أحدث إصدار (`Share-Flow.apk`) مع رقم النسخة، الحجم، تاريخ التحديث، بصمة SHA-256، وسجل الإصدارات السابقة، مع التعامل الآمن مع حالات التحميل، عدم توفر إصدار، أو تجاوز حد طلبات API.

---

## 5. إذا كان المستودع خاصاً (Private Repository Distribution Options)

روابط GitHub Releases داخل المستودعات الخاصة لا تقبل التحميل المباشر بدون مصادقة. لتوفير تحميل مباشر وآمن دون جعل الشيفرة المصدرية عامة، يدعم المشروع 3 حلول جاهزة:

### الخيار أ (مدعوم تلقائياً في `release.yml`): مستودع إصدارات عام مستقل للـ APK فقط
1. أنشئ مستودعاً عاماً فارغاً (بدون أي شيفرة مصدرية) مثل: `ow7ab/Share-Flow-Releases`.
2. في إعدادات المستودع الخاص `ow7ab/Share-Flow`:
   - أضف متغيراً (**Variables → Actions**): `PUBLIC_RELEASES_REPO = ow7ab/Share-Flow-Releases`
   - أضف سراً (**Secrets → Actions**): `PUBLIC_RELEASES_TOKEN` (رمز Fine-grained PAT بصلاحية `Contents: Read and write` على مستودع الإصدارات العام فقط).
3. في ملف `docs/config.js` غيّر `releasesRepo` إلى `"ow7ab/Share-Flow-Releases"`.
4. ستبقى الشيفرة المصدرية خاصة 100% بينما يُنشر فقط `Share-Flow.apk` و`version.json` للعامة.

### الخيار ب: استضافة `Share-Flow.apk` و`version.json` على مساحة تنزيل عامة مستقلة (Cloudflare R2 / Firebase Hosting / خادم HTTPS)
- ارفع الملفين (`Share-Flow.apk` و`version.json`) من مخرجات البناء إلى خادمك عبر HTTPS.
- ضع رابط `version.json` في `docs/config.js` داخل `customManifestUrl` وفي إعدادات التحديث السحابي بالتطبيق.

### الخيار ج: تحميل آمن مقصور على المستخدمين المصرح لهم فقط (Authorized Users Only)
- استخدم وسيط خادم آمن (مثل Cloudflare Worker أو Firebase Cloud Function) يتحقق أولاً من هوية المستخدم المصرح له (جلسة/تسجيل دخول)، ثم يجلب ملف `Share-Flow.apk` من GitHub Releases API (`Accept: application/octet-stream`) باستخدام توكن قراءة فقط مخزّن على الخادم الوسيط (لا يُوضع في المتصفح مطلقاً)، ويعيد بث ملف APK مباشرة للمستخدم المصرح له برابط مؤقت قصير الصلاحية.
