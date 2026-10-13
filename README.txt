جيرون - مشروع أندرويد (Android Studio)

الرفع على موقع بناء APK: ارفع هذا الـ ZIP كما هو (settings.gradle بالجذر). اختر JDK 17.
- compileSdk 34 / minSdk 24 / AGP 8.2.2 / Java 17.
- ما فيه gradle-wrapper.jar (ملف ثنائي). موقع البناء يستخدم Gradle حقه، وإذا يطلب wrapper نفّذ: gradle wrapper

قبل أول تشغيل: افتح supabase-setup.sql والصقه كله بـ Supabase > SQL Editor > Run (آمن تعيده).
هذا السكربت يخلي حسابك ezksnaruser_ag55 مشرف مباشرة.

مهم للإشعارات بالخلفية: Supabase > Authentication > Settings: ارفع JWT expiry إلى 604800 (أسبوع)،
حتى خدمة الخلفية تبقى تستلم لما التطبيق مسكّر.

الصلاحيات: الإشعارات، المايك، الموقع، والبلوتوث/Nearby (للشبكة القريبة). تنطلب عند الحاجة من الإعدادات.
الشبكة القريبة تحتاج خدمات Google Play (Nearby Connections).
