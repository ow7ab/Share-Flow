# Share Flow — Unified Single-Repository Production Architecture (`ow7ab/Share-Flow`)
# دليل البنية الموحّدة: الشيفرة المصدرية + الإصدارات الرسمية + الموقع الرسمي في `ow7ab/Share-Flow`

---

## 1. المعمارية الموحّدة الرسمية (Unified Production Architecture)

أصبح المستودع الأصلي **`ow7ab/Share-Flow`** هو المصدر الرسمي والوحيد لكامل مشروع **Share Flow** (الشيفرة المصدرية، الإصدارات، ملفات APK، والموقع الرسمي عبر GitHub Pages)، بينما يبقى المستودع السابق `ow7ab/Share-Flow-Releases` **أرشيفاً محفوظاً للقراءة فقط** دون حذف أي إصدار أو ملف.

| المكوّن | المستودع / الرابط الرسمي | الوصول | الدور |
|---|---|---|---|
| **المستودع الرسمي الموحّد** | `ow7ab/Share-Flow` (الفرع الرئيسي: `main`) | عام (`Public`) | الشيفرة المصدرية، Gradle، اختبارات الوحدة، GitHub Actions، الإصدارات (`Releases`)، ومجلد الموقع `/docs` |
| **موقع التحميل الرسمي** | `https://ow7ab.github.io/Share-Flow/` | عام (HTTPS + HSTS) | يُستضاف مباشرة عبر GitHub Pages من `ow7ab/Share-Flow` (`main` ← `/docs`) |
| **صفحة الإصدارات الرسمية** | `https://github.com/ow7ab/Share-Flow/releases` | عام (بدون مصادقة) | سجل جميع الإصدارات التاريخية والجديدة مع الاحتفاظ الكامل بالنسخ السابقة |
| **رابط التحميل المباشر لملف APK** | `https://github.com/ow7ab/Share-Flow/releases/latest/download/Share-Flow.apk` | عام (بدون مصادقة) | تحميل مباشر لآخر ملف APK موقّع دون ZIP ودون تسجيل دخول |
| **واجهة API للتحديث داخل التطبيق** | `https://api.github.com/repos/ow7ab/Share-Flow/releases` | عام (بدون مصادقة) | المصدر الرئيسي لنظام `CloudUpdateManager` داخل تطبيق Android |
| **ملف التحديث (`version.json`)** | `https://ow7ab.github.io/Share-Flow/version.json` (واحتياطي `https://github.com/ow7ab/Share-Flow/releases/latest/download/version.json`) | عام (بدون مصادقة) | يغذّي الموقع الرسمي والتطبيق تلقائياً بعد كل إصدار |
| **بصمة التحقق (`SHA-256`)** | `https://github.com/ow7ab/Share-Flow/releases/latest/download/Share-Flow.apk.sha256` | عام (بدون مصادقة) | للتحقق من سلامة ملف APK قبل التثبيت |
| **المستودع القديم (أرشيف فقط)** | `ow7ab/Share-Flow-Releases` | محفوظ كما هو (لا يُحذف) | مرجع أرشيفي قديم فقط — لم يعد مستخدماً في التطبيق أو الموقع أو Actions |

- **هوية التطبيق المحفوظة دون أي تعديل:**
  - `namespace`: `com.example`
  - `applicationId`: `com.shareflow`
  - `versionName`: `1.1.1` | `versionCode`: `24`
  - `minSdk`: `24` | `targetSdk`: `36`

---

## 2. أسرار ومتغيرات GitHub المطلوبة في `ow7ab/Share-Flow`

بفضل توحيد المشروع في مستودع واحد، **لم تعد هناك حاجة إطلاقاً إلى `PUBLIC_RELEASES_TOKEN` (PAT)**؛ إذ يستخدم سير العمل الرمز المدمج `${{ github.token }}` بصلاحية `contents: write` على `ow7ab/Share-Flow` فقط.

| اسم السر / المتغير | النوع | الوصف | إلزامي؟ |
|---|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | Secret | مفتاح التوقيع الإنتاجي الدائم بصيغة Base64 | **نعم** |
| `ANDROID_STORE_PASSWORD` | Secret | كلمة مرور Keystore | **نعم** |
| `ANDROID_KEY_PASSWORD` | Secret | كلمة مرور المفتاح الداخلي | **نعم** |
| `ANDROID_KEY_ALIAS` | Secret | الاسم المستعار للمفتاح (الافتراضي: `upload`) | اختياري |
| `EXPECTED_CERT_SHA256` | Secret | بصمة SHA-256 لشهادة التوقيع الدائمة لتثبيتها وحماية الترقية المباشرة | موصى به |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Secret | حساب خدمة Firebase لإرسال إشعارات التحديث الفورية عبر FCM HTTP v1 | اختياري (غير معطِّل للإصدار) |
| `SHAREFLOW_PORTAL_URL` | Variable | رابط الموقع الرسمي (الافتراضي: `https://ow7ab.github.io/Share-Flow/`) | اختياري |

---

## 3. نقل الإصدارات التاريخية من `ow7ab/Share-Flow-Releases` (نقرة واحدة، بدون إعادة بناء)

يتوفر سير عمل مخصص وآمن وقابل لإعادة التشغيل (Idempotent):
**Actions → `Migrate Historical Releases from Legacy Repo` → Run workflow**

يقوم السكربت `scripts/migrate_releases.py` بما يلي:
1. قراءة جميع الإصدارات والوسوم المنشورة من `ow7ab/Share-Flow-Releases` بترتيب زمني من الأقدم إلى الأحدث دون تعديل أو حذف المستودع القديم.
2. التحقق من الإصدارات الموجودة مسبقاً في `ow7ab/Share-Flow` (مثل `V1.0.13` المطابق تماماً في SHA-256) وتجاوزها بأمان دون استبدال.
3. تنزيل ملفات APK الأصلية كما هي دون إعادة بناء، التحقق من حجمها وبصمة **SHA-256**، رفعها إلى `ow7ab/Share-Flow` كمسودة ثم التحقق من بصمة الملف المرفوع قبل نشره.
4. حفظ الوسوم المستقلة (مثل `V1.0.20`) وتعيين أحدث إصدار مستقر (`V1.0.21`) كـ `Latest`.
5. دعم وضع المعاينة `dry_run: true` قبل التنفيذ الفعلي.

---

## 4. النشر الإنتاجي المؤتمت بالكامل (`.github/workflows/release.yml`)

يعمل سير العمل تلقائياً في الحالات الثلاث التالية:
1. **الدفع التلقائي من Google AI Studio إلى `main`:** عند تعديل `app/build.gradle.kts` برقم `versionName` و`versionCode` جديدين غير منشورين بعد، يُنشئ سير العمل الإصدار تلقائياً. أما دفعات الشيفرة العادية التي لا تغيّر الإصدار فيتم تجاوزها بنجاح خلال ثوانٍ دون أخطاء.
2. **دفع وسم دلالي (`vX.Y.Z`):**
   ```bash
   git tag -a v1.1.1 -m "Share Flow v1.1.1 (Build 24)"
   git push origin v1.1.1
   ```
3. **التشغيل اليدوي (`workflow_dispatch`):** مع دعم `dry_run: true` للاختبار الآمن دون نشر.

### خطوات التحقق الذرّي داخل `release.yml`:
1. فحص قبل البناء (`validate-prebuild`): التحقق من عدم تكرار الإصدار (بغض النظر عن حالة الأحرف `v`/`V`)، ثبات `applicationId = com.shareflow`، تطابق `versionName`، وزيادة `versionCode` بصرامة.
2. تشغيل اختبارات الموقع وسير العمل (`test-portal.js`, `test-release-workflow.js`, `sync-public-docs.sh --dry-run`) واختبارات Android (`:app:testDebugUnitTest`).
3. بناء وتوقيع `Release APK` و`AAB` والتحقق الصارم عبر `apksigner`، `zipalign`، `aapt2`، `jarsigner`، و`verify-postbuild`.
4. نشر ذرّي (`Draft → Asset Size & Presence Verify → Promote to Latest`) مع تراجع تلقائي (`trap cleanup_failed_draft ERR`) عند أي خطأ.
5. فحص التحميل العام غير المصادق عبر `curl` لرابط `https://github.com/ow7ab/Share-Flow/releases/latest/download/Share-Flow.apk`.
6. تحديث `docs/version.json` و`docs/latest-release.json` مباشرة على `main` (`[skip ci]`) لتحديث GitHub Pages تلقائياً.
7. إرسال إشعار FCM الفوري للمستخدمين وكتابة ملخص النشر وحذف الملفات الحساسة عبر `shred`.

---

## 5. الموقع الرسمي وإعداد النطاق المخصص (`shareflows.org`)

- **الاستضافة الحالية الفعّالة:** GitHub Pages مفعّل ويعمل الآن عبر HTTPS + HSTS على **`https://ow7ab.github.io/Share-Flow/`** مباشرة من `ow7ab/Share-Flow` (`main` ← `/docs`).
- **التحقق التلقائي للموقع:** يقوم `.github/workflows/sync-website.yml` تلقائياً عند كل تعديل في `docs/**` بتشغيل اختبارات البوابة وفحص الأمان ومنع تسرب الأسرار أو الروابط القديمة.
- **ملاحظة حول النطاق المخصص `shareflows.org`:** وُجد ملف `docs/CNAME` بقيمة `shareflows.org` في المستودع القديم `ow7ab/Share-Flow-Releases`، ولكن سجلات DNS للنطاق `shareflows.org` غير مهيأة حالياً. لربطه لاحقاً بالمستودع الموحّد `ow7ab/Share-Flow`:
  1. أزل النطاق المخصص أولاً من **Settings → Pages** في المستودع القديم `ow7ab/Share-Flow-Releases`.
  2. أضف سجلات DNS للنطاق `shareflows.org` نحو GitHub Pages (`A` records: `185.199.108.153`, `185.199.109.153`, `185.199.110.153`, `185.199.111.153` أو `CNAME` لـ `www` نحو `ow7ab.github.io`).
  3. أضف `shareflows.org` في **Settings → Pages** داخل `ow7ab/Share-Flow` وفعّل **Enforce HTTPS**.

---

## 6. استمرارية التحديث المباشر فوق النسخة المثبّتة

لضمان الترقية المباشرة دون فقدان بيانات المستخدمين:
1. الحفاظ الدائم على شهادة التوقيع (`ANDROID_KEYSTORE_BASE64` + `EXPECTED_CERT_SHA256`).
2. الحفاظ الدائم على `applicationId = "com.shareflow"`.
3. زيادة `versionCode` و`versionName` في `app/build.gradle.kts` مع كل إصدار جديد.
