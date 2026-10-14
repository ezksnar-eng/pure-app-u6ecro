// أدوات الإدارة: إعادة تعيين كلمة سر حساب (للمشرفين فقط). ما نحفظ كلمات السر أبدًا.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const J = (o: unknown, s = 200) =>
  new Response(JSON.stringify(o), { status: s, headers: { ...CORS, "Content-Type": "application/json" } });

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  try {
    const url = Deno.env.get("SUPABASE_URL")!;
    const caller = createClient(url, Deno.env.get("SUPABASE_ANON_KEY")!, {
      global: { headers: { Authorization: req.headers.get("Authorization") || "" } },
    });
    const { data: { user } } = await caller.auth.getUser();
    if (!user) return J({ error: "auth" }, 401);

    const svc = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
    const { data: prof } = await svc.from("profiles").select("is_admin").eq("id", user.id).maybeSingle();
    if (!prof?.is_admin) return J({ error: "forbidden" }, 403);

    const body = await req.json();
    if (body.action === "reset_password") {
      const uid = String(body.uid || "");
      if (!/^[0-9a-f-]{36}$/i.test(uid)) return J({ error: "uid" }, 400);
      const alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
      const rnd = crypto.getRandomValues(new Uint8Array(10));
      const temp = Array.from(rnd).map((x) => alphabet[x % alphabet.length]).join("");
      const { error } = await svc.auth.admin.updateUserById(uid, { password: temp });
      if (error) return J({ error: error.message }, 400);
      await svc.from("admin_log").insert({ admin: user.id, action: "reset_password", target: uid });
      return J({ temp });
    }
    return J({ error: "unknown_action" }, 400);
  } catch (e) {
    return J({ error: String(e) }, 500);
  }
});
