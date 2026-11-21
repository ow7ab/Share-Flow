# دليل البناء والتوقيع والنشر التلقائي لتطبيق Share Flow
# Share Flow Automated Release & Direct APK Distribution Guide

---

## 1. الروابط المباشرة لأحدث إصدار (Direct Release URLs)

عند نشر الإصدار عبر `.github/workflows/release.yml` في المستودع `ow7ab/Share-Flow`، تتحدث الروابط التالية تلقائياً دون الحاجة لأي ملفات ZIP:

- **رابط التحميل المباشر لملف APK (أحدث إصدار دائماً):**
  `https://github.com/ow7ab/Share-Flow/releases/latest/download/Share-Flow.apk`
- **رابط ملف التحديث السحابي للتطبيق (`version.json`):**
  `https://github.com/ow7ab/Share-Flow/releases/latest/download/version.json`
- **رابط بصمة التحقق SHA-256:**
  `https://github.com/ow7ab/Share-Flow/releases/latest/download/Share-Flow.apk.sha256`
- **موقع التحميل المباشر (GitHub Pages من مجلد `/docs`):**
  `https://ow7ab.github.io/Share-Flow/`

---

## 2. إعداد أسرار التوقيع في GitHub Secrets (مرة واحدة فقط)

اذهب إلى المستودع على GitHub:
**Settings → Secrets and variables → Actions → New repository secret**

أضف الأسرار التالية (لا تقم أبداً برفع ملف `.jks` أو كلمات المرور إلى الكود):

| اسم السر (Secret Name) | الوصف | إلزامي؟ |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | محتوى مفتاح التوقيع الدائم محولاً إلى Base64 عبر الأمر `base64 -w 0 my-upload-key.jks` | نعم |
| `ANDROID_STORE_PASSWORD` | كلمة مرور ملف Keystore | نعم |
| `ANDROID_KEY_PASSWORD` | كلمة مرور المفتاح الداخلي (Key Password) | نعم |
| `ANDROID_KEY_ALIAS` | الاسم المستعار للمفتاح داخل Keystore (الافتراضي `upload` مطابق لـ `app/build.gradle.kts`) | اختياري |
| `EXPECTED_CERT_SHA256` | بصمة SHA-256 لشهادة التوقيع المعتمدة لمنع نشر أي إصدار بشهادة مختلفة | اختياري (موصى به) |

> **حماية توافق التحديثات:** سير العمل لا يُنشئ مفتاح توقيع جديداً أبداً. إذا غاب أي سر أساسي يفشل البناء فوراً دون نشر نسخة غير متوافقة.

---

## 3. كيفية تشغيل النشر التلقائي

يعمل سير العمل `.github/workflows/release.yml` تلقائياً في ثلاث حالات:
1. عند كل دفع (Push) جديد إلى فرع `main`.
2. عند إنشاء وسم إصدار جديد يبدأ بـ `v` مثل `v1.0.2`.
3. يدوياً من تبويب **Actions → ShareFlow Automated Release Build & APK Publish → Run workflow** (مع إمكانية كتابة ملاحظات إصدار بالعربية والإنجليزية).

قبل النشر يتحقق سير العمل تلقائياً من:
1. نجاح جميع اختبارات الوحدة واختبارات Robolectric (`./gradlew :app:testDebugUnitTest`).
2. نجاح بناء Release APK وAAB (`./gradlew :app:assembleRelease :app:bundleRelease`).
3. صحة التوقيع الرقمي عبر `apksigner verify --verbose --print-certs`.
4. محاذاة الملف (`zipalign -c -v 4`) لضمان التثبيت المباشر على Android 11+.
5. تطابق معرف الحزمة (`com.aistudio.shareflow.kxrvnm`) ورقم الإصدار عبر `aapt2 dump badging`.
6. توليد والتحقق من `version.json` ثم رفع `Share-Flow.apk` مباشرة (بدون ZIP) داخل GitHub Releases مع الاحتفاظ بكافة الإصدارات السابقة.

---

## 4. تفعيل موقع التحميل المباشر (GitHub Pages)

1. في إعدادات المستودع: **Settings → Pages**.
2. اختر **Source: Deploy from a branch**.
3. اختر الفرع **`main`** والمجلد **`/docs`** ثم اضغط **Save**.
4. سيعرض الموقع فوراً أحدث إصدار (`Share-Flow.apk`) مع رقم النسخة، الحجم، تاريخ التحديث، وبصمة SHA-256، وسجل الإصدارات السابقة.

---

## 5. إذا كان المستودع خاصاً (Private Repository Distribution Options)

روابط GitHub Releases داخل المستودعات الخاصة لا تقبل التحميل المباشر بدون مصادقة. لتوفير تحميل مباشر وآمن دون جعل الشيفرة المصدرية عامة، يدعم هذا المشروع 3 خيارات جاهزة:

### الخيار أ (الأسهل والمدعوم تلقائياً في `release.yml`): مستودع إصدارات عام مستقل
1. أنشئ مستودعاً عاماً فارغاً (بدون أي شيفرة مصدرية) مثل: `ow7ab/Share-Flow-Releases`.
2. في إعدادات المستودع الخاص `ow7ab/Share-Flow`:
   - أضف متغيراً (**Variables → Actions**): `PUBLIC_RELEASES_REPO = ow7ab/Share-Flow-Releases`
   - أضف سراً (**Secrets → Actions**): `PUBLIC_RELEASES_TOKEN` (رمز Fine-grained PAT بصلاحية `Contents: Read and write` على مستودع الإصدارات العام فقط).
3. في ملف `docs/config.js` غيّر `releasesRepo` إلى `"ow7ab/Share-Flow-Releases"`.
4. ستبقى الشيفرة المصدرية خاصة 100% بينما يُنشر فقط `Share-Flow.apk` و`version.json` للعامة.

### الخيار ب: استضافة `Share-Flow.apk` و`version.json` على مساحة تخزين عامة مستقلة (Cloudflare R2 / Firebase Hosting / خادمك السوداني)
- ارفع الملفين (`Share-Flow.apk` و`version.json`) من مخرجات البناء إلى خادمك عبر HTTPS.
- ضع رابط `version.json` في `docs/config.js` داخل `customManifestUrl` وفي إعدادات التحديث السحابي بالتطبيق.

### الخيار ج: تحميل مقصور على المستخدمين المصرح لهم فقط (Authorized Users Only)
- استخدم وسيط خادم آمن (مثل Cloudflare Worker أو Cloud Function) يتحقق من جلسة المستخدم المصرح له ثم يطلب أصل الإصدار من GitHub API (`Accept: application/octet-stream`) باستخدام توكن قراءة فقط مخزّن في خادم الوسيط (وليس في المتصفح أبداً)، ويعيد بث ملف `Share-Flow.apk` للمستخدم برابط مؤقت قصير الصلاحية.
