// امبد: بوابة الذكاء الاصطناعي (مفاتيحك تبقى بالسيرفر، ما تنكشف بالتطبيق)
// الأسرار (Edge Functions > Secrets): GEMINI_API_KEY  (يكفي هذا)
// اختياري كاحتياط: GROQ_API_KEY ، OPENROUTER_API_KEY
// اختياري: GEMINI_MODEL (افتراضي gemini-2.5-flash) ، AI_ORDER (افتراضي gemini,groq,openrouter)
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const J = (o: unknown, s = 200) =>
  new Response(JSON.stringify(o), { status: s, headers: { ...CORS, "Content-Type": "application/json" } });

const hits = new Map<string, number[]>();

type Msg = { role: string; content: string };

async function gemini(system: string, msgs: Msg[], images: string[]): Promise<string | null> {
  const key = Deno.env.get("GEMINI_API_KEY");
  if (!key) return null;
  const models = [Deno.env.get("GEMINI_MODEL") || "gemini-2.5-flash", "gemini-2.5-flash-lite"];
  const contents = msgs.map((m, i) => {
    const parts: unknown[] = [{ text: m.content }];
    if (i === msgs.length - 1 && m.role === "user") {
      for (const b64 of images) parts.push({ inline_data: { mime_type: "image/jpeg", data: b64 } });
    }
    return { role: m.role === "assistant" ? "model" : "user", parts };
  });
  for (const model of models) {
    try {
      const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": key },
        body: JSON.stringify({
          systemInstruction: { parts: [{ text: system }] },
          contents,
          generationConfig: { temperature: 0.7, maxOutputTokens: 900 },
        }),
      });
      if (!r.ok) continue;
      const j = await r.json();
      const t = (j.candidates?.[0]?.content?.parts || []).map((p: { text?: string }) => p.text || "").join("").trim();
      if (t) return t;
    } catch (_) { /* next model */ }
  }
  return null;
}

async function openaiStyle(url: string, key: string, model: string, system: string, msgs: Msg[], images: string[], extra: Record<string, string> = {}) {
  const messages: unknown[] = [{ role: "system", content: system }];
  msgs.forEach((m, i) => {
    if (i === msgs.length - 1 && m.role === "user" && images.length) {
      messages.push({
        role: "user",
        content: [{ type: "text", text: m.content }, ...images.map((b) => ({ type: "image_url", image_url: { url: "data:image/jpeg;base64," + b } }))],
      });
    } else messages.push({ role: m.role, content: m.content });
  });
  try {
    const r = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: "Bearer " + key, ...extra },
      body: JSON.stringify({ model, messages, temperature: 0.7, max_tokens: 900 }),
    });
    if (!r.ok) return null;
    const j = await r.json();
    return (j.choices?.[0]?.message?.content || "").trim() || null;
  } catch (_) {
    return null;
  }
}

async function groq(system: string, msgs: Msg[], images: string[]) {
  const key = Deno.env.get("GROQ_API_KEY");
  if (!key) return null;
  const model = images.length
    ? (Deno.env.get("GROQ_VISION_MODEL") || "meta-llama/llama-4-scout-17b-16e-instruct")
    : (Deno.env.get("GROQ_MODEL") || "llama-3.3-70b-versatile");
  return openaiStyle("https://api.groq.com/openai/v1/chat/completions", key, model, system, msgs, images);
}

async function openrouter(system: string, msgs: Msg[], images: string[]) {
  const key = Deno.env.get("OPENROUTER_API_KEY");
  if (!key) return null;
  const model = Deno.env.get("OPENROUTER_MODEL") || "google/gemma-3-27b-it:free";
  return openaiStyle("https://openrouter.ai/api/v1/chat/completions", key, model, system, msgs, images, { "X-Title": "Jeron" });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  try {
    const auth = req.headers.get("Authorization") || "";
    const sb = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
      global: { headers: { Authorization: auth } },
    });
    const { data: { user } } = await sb.auth.getUser();
    if (!user) return J({ error: "auth" }, 401);

    const now = Date.now();
    const arr = (hits.get(user.id) || []).filter((t) => now - t < 60000);
    if (arr.length >= 20) return J({ error: "rate" }, 429);
    arr.push(now);
    hits.set(user.id, arr);

    const body = await req.json();
    const system = String(body.system || "").slice(0, 8000);
    const msgs: Msg[] = (Array.isArray(body.messages) ? body.messages : []).slice(-14).map((m: Msg) => ({
      role: m.role === "assistant" ? "assistant" : "user",
      content: String(m.content || "").slice(0, 6000),
    }));
    const images: string[] = (Array.isArray(body.images) ? body.images : []).slice(0, 2).map(String).filter((b) => b.length < 2_500_000);
    if (!msgs.length) return J({ error: "empty" }, 400);

    const order = (Deno.env.get("AI_ORDER") || "gemini,groq,openrouter").split(",").map((s) => s.trim());
    const fns: Record<string, (s: string, m: Msg[], i: string[]) => Promise<string | null>> = { gemini, groq, openrouter };
    for (const name of order) {
      const f = fns[name];
      if (!f) continue;
      const text = await f(system, msgs, images);
      if (text) return J({ text, provider: name });
    }
    return J({ error: "all_failed" }, 502);
  } catch (e) {
    return J({ error: String(e) }, 500);
  }
});
