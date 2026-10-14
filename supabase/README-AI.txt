تشغيل الذكاء الاصطناعي الحقيقي لامبد + استرجاع الحسابات (مرة وحدة، 5 دقايق)

1) مفتاح Gemini مجاني: ادخل aistudio.google.com/apikey وسوّ مفتاح (مجاني، ما يحتاج بطاقة).
2) Supabase > Edge Functions > Create a new function باسم:  embed-ai
   الصق محتوى الملف  supabase/functions/embed-ai/index.ts  واضغط Deploy.
3) Supabase > Edge Functions > Secrets: أضف  GEMINI_API_KEY = مفتاحك.
   (اختياري كاحتياط: GROQ_API_KEY من console.groq.com ، أو OPENROUTER_API_KEY من openrouter.ai)
4) لاسترجاع الحسابات: سوّ function ثانية باسم  admin-tools  والصق  supabase/functions/admin-tools/index.ts
   (ما تحتاج سر إضافي، مفتاح الخدمة يجي تلقائيًا من Supabase).

ملاحظات:
- المفتاح يبقى بالسيرفر، التطبيق ما يشوفه. لا تحطه بأي ملف بالـGitHub.
- حدود Gemini المجانية تُحسب على مشروعك كله (تقريبًا 10-15 طلب بالدقيقة و250-1000 باليوم حسب الموديل).
- إذا ما نشرت الـfunction، امبد يرجع للخدمة المجانية القديمة (غير مضمونة).
