package com.jeron.app

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** امبد المحلي: نموذج Gemma 4 يشتغل على الجهاز نفسه (بدون نت، بدون API، بدون انتظار). */
interface LlmCallback {
    fun onToken(token: String)
    fun onDone(error: String?)
}

interface DlCallback {
    fun onProgress(pct: Int)
    fun onFinish(error: String?)
}

object LlmEngine {
    private val exec = Executors.newSingleThreadExecutor()
    private val dlExec = Executors.newSingleThreadExecutor()
    private var engine: Engine? = null

    @Volatile var downloading = false
    @Volatile var pct = 0
    @Volatile var cancel = false
    @Volatile var loading = false

    private fun file(c: Context) = File(c.filesDir, "llm/model.litertlm")

    @JvmStatic
    fun ready(c: Context): Boolean {
        val f = file(c)
        return f.exists() && f.length() > 200_000_000L
    }

    @JvmStatic
    fun status(c: Context): String {
        val f = file(c)
        return "{\"ready\":" + ready(c) + ",\"downloading\":" + downloading + ",\"pct\":" + pct +
            ",\"loading\":" + loading + ",\"size\":" + (if (f.exists()) f.length() else 0) + "}"
    }

    @JvmStatic
    fun delete(c: Context) {
        try {
            engine?.close()
        } catch (_: Throwable) {
        }
        engine = null
        file(c).delete()
        File(c.filesDir, "llm/model.part").delete()
        pct = 0
    }

    @JvmStatic
    fun cancelDownload() {
        cancel = true
    }

    /** تنزيل مع استئناف تلقائي لو انقطع. */
    @JvmStatic
    fun download(c: Context, url: String, cb: DlCallback) {
        if (downloading) return
        downloading = true
        cancel = false
        dlExec.execute {
            var err: String? = null
            try {
                val dir = File(c.filesDir, "llm")
                dir.mkdirs()
                val part = File(dir, "model.part")
                var have = if (part.exists()) part.length() else 0L
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code != 200 && code != 206) throw RuntimeException("HTTP $code")
                if (code == 200) have = 0
                val total = have + conn.contentLengthLong
                if (c.filesDir.usableSpace < conn.contentLengthLong + 50_000_000L) throw RuntimeException("المساحة لا تكفي")
                conn.inputStream.use { ins ->
                    FileOutputStream(part, have > 0).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = have
                        var last = -1
                        while (true) {
                            if (cancel) throw RuntimeException("cancel")
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val p = if (total > 0) (done * 100 / total).toInt() else 0
                            if (p != last) {
                                last = p
                                pct = p
                                cb.onProgress(p)
                            }
                        }
                    }
                }
                val dest = file(c)
                dest.delete()
                if (!part.renameTo(dest)) throw RuntimeException("rename")
                pct = 100
            } catch (e: Throwable) {
                err = e.message ?: "error"
            }
            downloading = false
            cb.onFinish(err)
        }
    }

    private fun ensureEngine(c: Context): Engine {
        engine?.let { return it }
        loading = true
        try {
            val e = Engine(
                EngineConfig(
                    modelPath = file(c).absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = c.cacheDir.path
                )
            )
            e.initialize()
            engine = e
            return e
        } finally {
            loading = false
        }
    }

    /** historyJson: [{"r":"u"|"m","t":"..."}] */
    @JvmStatic
    fun generate(c: Context, system: String, historyJson: String, prompt: String, cb: LlmCallback) {
        exec.execute {
            try {
                val e = ensureEngine(c)
                val initial = ArrayList<Message>()
                val h = JSONArray(historyJson)
                for (i in 0 until h.length()) {
                    val o = h.getJSONObject(i)
                    val t = o.optString("t")
                    if (t.isEmpty()) continue
                    initial.add(if (o.optString("r") == "u") Message.user(t) else Message.model(t))
                }
                val conv = e.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(system),
                        initialMessages = initial,
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8)
                    )
                )
                conv.sendMessageAsync(prompt, object : MessageCallback {
                    override fun onMessage(message: Message) {
                        cb.onToken(message.toString())
                    }

                    override fun onDone() {
                        exec.execute { try { conv.close() } catch (_: Throwable) {} }
                        cb.onDone(null)
                    }

                    override fun onError(throwable: Throwable) {
                        exec.execute { try { conv.close() } catch (_: Throwable) {} }
                        cb.onDone(throwable.message ?: "error")
                    }
                })
            } catch (t: Throwable) {
                cb.onDone(t.message ?: "error")
            }
        }
    }
}
