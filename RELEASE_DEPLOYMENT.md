# Share Flow — Private Source + Public Releases + GitHub Pages
# دليل بنية الإنتاج: مستودع شيفرة خاص + مستودع إصدارات عام + GitHub Pages

---

## 1. معمارية التوزيع والإنتاج (Production Architecture)

| المكوّن | المستودع / الرابط | الخصوصية (Visibility) | الغرض الحصري |
|---|---|---|---|
| **الشيفرة المصدرية** | `ow7ab/Share-Flow` | **PRIVATE (خاص دائماً)** | شيفرة تطبيق Android، إعدادات Gradle، اختبارات الوحدة، وسير عمل GitHub Actions |
| **التوزيع العام والصفحات** | `ow7ab/Share-Flow-Releases` | **PUBLIC (عام)** | نشر ملفات APK الموقّعة عبر GitHub Releases + استضافة ملفات الموقع الثابتة في `/docs` فقط |
| **موقع التحميل الرسمي** | `https://ow7ab.github.io/Share-Flow-Releases/` | عام (بدون مصادقة) | GitHub Pages من فرع `main` ومجلد `/docs` |
| **صفحة الإصدارات العامة** | `https://github.com/ow7ab/Share-Flow-Releases/releases` | عام (بدون مصادقة) | سجل الإصدارات الكامل مع الاحتفاظ بالنسخ السابقة |
| **رابط التحميل المباشر لملف APK** | `https://github.com/ow7ab/Share-Flow-Releases/releases/latest/download/Share-Flow.apk` | عام (بدون مصادقة) | تحميل مباشر لملف `Share-Flow.apk` دون ZIP ودون تسجيل دخول GitHub |
| **رابط التحديث الرسمي (`version.json`)** | `https://ow7ab.github.io/Share-Flow-Releases/version.json` (مع احتياطي تلقائي `https://github.com/ow7ab/Share-Flow-Releases/releases/latest/download/version.json`) | عام (بدون مصادقة) | مصدر بيانات موحّد يغذّي الموقع الرسمي و`CloudUpdateManager` داخل التطبيق تلقائياً دون إدخال أي رابط يدوياً |
| **بصمة التحقق (`SHA-256`)** | `https://github.com/ow7ab/Share-Flow-Releases/releases/latest/download/Share-Flow.apk.sha256` | عام (بدون مصادقة) | للتحقق من سلامة ملف APK قبل التثبيت |

- **هوية التطبيق المحفوظة دون أي تعديل:**
  - `namespace`: `com.example`
  - `applicationId`: `com.aistudio.shareflow.kxrvnm`
  - `versionName`: `1.0.11` | `versionCode`: `12`
  - `minSdk`: `24` | `targetSdk`: `36`

> **قواعد حماية الشيفرة والبيانات:** لا يُنسخ أي ملف Kotlin أو Gradle أو XML أو `google-services.json` أو Keystore أو سر إلى `ow7ab/Share-Flow-Releases` أبداً. يتحقق السكربت `scripts/sync-public-docs.sh` بقائمة سماح صارمة (Allowlist) وفحص للأسرار قبل أي دفع إلى المستودع العام.

---

## 2. متغيرات وأسرار GitHub المطلوبة في المستودع الخاص (`ow7ab/Share-Flow`)

### أ) متغيرات المستودع (**Settings → Secrets and variables → Actions → Variables**)

| اسم المتغير (Variable) | القيمة المطلوبة | إلزامي؟ |
|---|---|---|
| `PUBLIC_RELEASES_REPO` | `ow7ab/Share-Flow-Releases` | اختياري (مُعدّ افتراضياً على `ow7ab/Share-Flow-Releases`) |
| `SHAREFLOW_PORTAL_URL` | `https://ow7ab.github.io/Share-Flow-Releases/` | اختياري (مُعدّ افتراضياً) |

### ب) أسرار المستودع (**Settings → Secrets and variables → Actions → Secrets**)

| اسم السر (Secret) | الوصف وكيفية استخراجه | إلزامي؟ |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | محتوى مفتاح التوقيع الدائم محولاً إلى Base64: `base64 -w 0 shareflow-release.jks` | **نعم** |
| `ANDROID_STORE_PASSWORD` | كلمة مرور ملف Keystore | **نعم** |
| `ANDROID_KEY_PASSWORD` | كلمة مرور المفتاح الداخلي (Key Password) | **نعم** |
| `PUBLIC_RELEASES_TOKEN` | Fine-grained Personal Access Token مقيّد **فقط** بمستودع `ow7ab/Share-Flow-Releases` بصلاحية `Contents: Read and write` (و`Metadata: Read-only`) | **نعم** |
| `ANDROID_KEY_ALIAS` | الاسم المستعار للمفتاح داخل Keystore (يُستخدم `upload` تلقائياً عند تركه فارغاً) | اختياري (افتراضي: `upload`) |
| `EXPECTED_CERT_SHA256` | بصمة SHA-256 لشهادة التوقيع الدائمة لتثبيت الشهادة بشكل صريح (ويتم التحقق تلقائياً من مطابقة الشهادة مع آخر إصدار منشور دائماً) | اختياري (موصى به) |
| `ANDROID_DEBUG_KEYSTORE_BASE64` | محتوى `debug.keystore` الدائم بصيغة Base64 لتثبيت توقيع نسخ الاختبار (`blank.yml`) بشكل دائم دون انتهاء صلاحية الكاش | اختياري (موصى به لنسخ Debug) |

> **لا تقم أبداً** بوضع `PUBLIC_RELEASES_TOKEN` أو أي مفتاح داخل `docs/` أو شيفرة المتصفح. يعمل الموقع العام بالوصول غير المصادق إلى `https://api.github.com/repos/ow7ab/Share-Flow-Releases/releases/latest`.

---

## 3. خطوات التهيئة اليدوية على GitHub (مرة واحدة فقط)

1. **التحقق من خصوصية مستودع الشيفرة:**
   - تأكد أن `ow7ab/Share-Flow` مضبوط على **Private** ولا تقم بتغيير خصوصيته أبداً.
2. **إنشاء مستودع التوزيع العام:**
   - أنشئ المستودع `ow7ab/Share-Flow-Releases` بوضعية **Public** مع تهيئة فرع `main` (يمكن اختيار *Add a README file* عند الإنشاء).
   - **لا تنقل** أي ملفات شيفرة مصدرية أو ملفات Android إلى هذا المستودع.
3. **إنشاء Fine-grained Personal Access Token (`PUBLIC_RELEASES_TOKEN`):**
   - اذهب إلى: **GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**.
   - **Repository access:** اختر **Only select repositories** ← اختر `ow7ab/Share-Flow-Releases` فقط.
   - **Repository permissions:**
     - `Contents`: **Read and write** (للنشر في Releases وتحديث مجلد `/docs` على `main`).
     - `Metadata`: **Read-only** (مطلوب تلقائياً).
   - انسخ الرمز واحفظه فوراً في أسرار `ow7ab/Share-Flow` باسم `PUBLIC_RELEASES_TOKEN`.
4. **إضافة المتغيرات والأسرار في `ow7ab/Share-Flow`:**
   - أضف المتغير `PUBLIC_RELEASES_REPO = ow7ab/Share-Flow-Releases`.
   - أضف الأسرار الستة الموضحة في الجدول أعلاه (`ANDROID_KEYSTORE_BASE64`, `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_PASSWORD`, `ANDROID_KEY_ALIAS`, `EXPECTED_CERT_SHA256`, `PUBLIC_RELEASES_TOKEN`).
   - لاستخراج بصمة `EXPECTED_CERT_SHA256` من مفتاحك الدائم محلياً:
     ```bash
     keytool -list -v -keystore shareflow-release.jks -alias upload | grep "SHA256:"
     ```
5. **تفعيل GitHub Pages في `ow7ab/Share-Flow-Releases`:**
   - بعد أول مزامنة لمجلد `/docs` (عبر `sync-website.yml` أو أول إصدار من `release.yml`):
   - افتح `ow7ab/Share-Flow-Releases` ← **Settings → Pages**.
   - **Source:** `Deploy from a branch`.
   - **Branch:** `main` | **Folder:** `/docs` ← اضغط **Save**.
   - انتظر 1–2 دقيقة ثم تحقق من الرابط: `https://ow7ab.github.io/Share-Flow-Releases/`.

---

## 4. تشغيل النشر الإنتاجي (Controlled Release Workflow)

لا يقوم `.github/workflows/release.yml` بنشر إصدار تلقائياً عند كل commit عادي على `main`؛ بل يعمل فقط عند:
1. **دفع وسم إصدار دلالي (Semantic Tag):** مثل `v1.0.11` (يجب أن يطابق `versionName` في `app/build.gradle.kts`):
   ```bash
   git tag -a v1.0.11 -m "Share Flow v1.0.11 (Build 12)"
   git push origin v1.0.11
   ```
2. **التشغيل اليدوي المحكوم (`workflow_dispatch`):** من تبويب **Actions → ShareFlow Production Release (Private Source -> Public APK & Pages) → Run workflow** مع إدخال ملاحظات الإصدار بالعربية والإنجليزية.

### ما يتحقق منه سير العمل تلقائياً قبل أي نشر:
1. وجود جميع الأسرار الستة والمتغير `PUBLIC_RELEASES_REPO`، ومنع تطابق مستودع التوزيع مع المستودع الخاص.
2. التحقق عبر GitHub API أن `ow7ab/Share-Flow-Releases` مستودع **Public** فعلاً وأن وسم الإصدار المطلوب **غير موجود مسبقاً** (لا يتم الكتابة فوق أي إصدار سابق أبداً).
3. فك تشفير Keystore داخل `${RUNNER_TEMP}` بصلاحية `600` والتحقق من `PrivateKeyEntry` عبر `keytool -storepass:env`.
4. اجتياز اختبارات بوابة التحميل (`node --test ./scripts/test-portal.js`) وفحص قائمة السماح والأسرار (`bash ./scripts/sync-public-docs.sh --dry-run`).
5. اجتياز اختبارات وحدة Android (`./gradlew :app:testDebugUnitTest`).
6. بناء Release APK وAAB (`./gradlew :app:assembleRelease :app:bundleRelease`).
7. التحقق الصارم عبر:
   - `apksigner verify --verbose --print-certs` + مطابقة `EXPECTED_CERT_SHA256`.
   - `zipalign -c -v 4`.
   - `aapt2 dump badging` (تطابق `com.aistudio.shareflow.kxrvnm`، تطابق `v${VERSION_NAME}` مع الوسم، ووجود النشاط الرئيسي).
   - `jarsigner -verify -strict` لملف AAB.
   - عدم تراجع `versionCode` وعدم تغيّر شهادة التوقيع مقارنة بآخر `version.json` منشور.
8. رفع `Share-Flow.apk` (بشكل مستقل دون ZIP)، و`Share-Flow.apk.sha256`، و`version.json` إلى `ow7ab/Share-Flow-Releases` عبر GitHub Releases (`--latest`).
9. التحقق الفعلي (غير المصادق) عبر `curl -fsSLI` أن الرابط `https://github.com/ow7ab/Share-Flow-Releases/releases/latest/download/Share-Flow.apk` يُرجع ملف APK ثنائياً مطابق الحجم وليس صفحة HTML أو 404.
10. مزامنة مجلد `/docs` الثابت فقط (مع نسختي `version.json` و`latest-release.json`) إلى فرع `main` في `ow7ab/Share-Flow-Releases`.
11. حذف Keystore المؤقت والملفات الحساسة دائماً (`if: always()`).

---

## 5. تحديث الموقع ولقطات التطبيق (`sync-website.yml`)

إذا قمت بتعديل تصميم أو نصوص الموقع داخل `/docs` فقط، أو عند إضافة لقطات شاشة حقيقية من هاتف Android داخل `docs/screenshots/` وتسجيلها في مصفوفة `galleryItems` داخل `docs/config.js`، يمكنك تشغيل سير العمل اليدوي:
**Actions → ShareFlow Public Website Sync (Docs Only) → Run workflow**
وسيقوم بتشغيل اختبارات البوابة وفحص السماح/الأسرار ثم دفع ملفات `/docs` الثابتة فقط إلى `ow7ab/Share-Flow-Releases`.

---

## 6. ضمان التحديث المباشر فوق النسخة القديمة دون حذف التطبيق (In-Place Update Continuity)

للسماح لنظام Android بتثبيت التحديث الجديد مباشرة فوق النسخة المثبّتة (**تحديث / Update**) دون حذف النسخة القديمة ودون فقدان بيانات المستخدم، يفرض Android ثلاثة شروط إلزامية:

1. **تطابق شهادة التوقيع (Same Signing Certificate):**
   - **نسخ الاختبار (`Debug APK` عبر `blank.yml`):** يقرأ سير العمل المفتاح الدائم وفق الأولوية التالية: السر `ANDROID_DEBUG_KEYSTORE_BASE64` ← ملف `debug.keystore.base64` إن وُجد ← نسخة `debug.keystore` المحفوظة في كاش GitHub Actions (`shareflow-ci-debug-keystore-v1`)، ثم يتحقق عبر `apksigner` من مطابقة بصمة SHA-256 في كل بناء. ولضمان استمرارية دائمة لا تتأثر بانتهاء الكاش (بعد 7 أيام من عدم النشاط)، أضف السر `ANDROID_DEBUG_KEYSTORE_BASE64` في إعدادات المستودع.
   - **نسخ الإنتاج الرسمية (`Release APK` عبر `release.yml` والموقع):** يوقّع `.github/workflows/release.yml` دائماً بالمفتاح الإنتاجي الدائم `ANDROID_KEYSTORE_BASE64` ويتحقق من مطابقة `EXPECTED_CERT_SHA256` وشهادة الإصدار السابق قبل النشر. لذلك يتحدّث أي `Release APK` جديد مباشرة فوق الإصدار السابق.
   - **ملاحظة انتقال لمرة واحدة فقط:** إذا كانت النسخة المثبّتة حالياً على هاتفك مبنية بمفتاح مختلف (مثلاً نسخة `Debug` قديمة قبل الإصلاح أو عند الانتقال الأول من `Debug` إلى `Release`)، فسيطلب Android حذفها **مرة واحدة فقط** عند الانتقال، وبعدها ستتحدّث جميع الإصدارات اللاحقة من نفس المسار مباشرة دون حذف التطبيق أبداً.
2. **تطابق معرّف الحزمة (`applicationId`):** ثابت دائماً على `com.aistudio.shareflow.kxrvnm`.
3. **زيادة رقم البناء (`versionCode`):** يجب زيادة `versionCode` و`versionName` في `app/build.gradle.kts` عند كل إصدار جديد (مثلاً `12 -> 13` و`1.0.11 -> 1.0.12`).

