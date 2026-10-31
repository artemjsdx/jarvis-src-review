package app.jarvis.core.llm;

import app.jarvis.core.log.Journal;
import app.jarvis.core.net.Http;
import app.jarvis.core.net.SseReader;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.net.ssl.SSLException;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * OpenAI-compatible chat client with streaming SSE, tool-call fragment assembly, cancellation,
 * retry/backoff and adaptive removal of parameters a provider rejects. One instance per provider config.
 */
public final class OpenAiClient {
    private final Journal journal;
    private final ParamMemory memory;
    private volatile boolean swapMaxTokensKey;
    /** null = follow the preset; learned from provider errors that mention reasoning_content. */
    private volatile Boolean echoOverride;

    public OpenAiClient(Journal journal) { this(journal, null); }

    public OpenAiClient(Journal journal, ParamMemory memory) {
        this.journal = journal == null ? Journal.NOP : journal;
        this.memory = memory == null ? new ParamMemory(null) : memory;
    }

    private static final class Progress { volatile boolean delivered; }

    // ------------------------------------------------------------------------------------------ chat
    public LlmResult chat(LlmConfig cfg, LlmRequest req, LlmListener listener, CancelToken cancel) throws LlmException {
        final LlmListener l = listener == null ? LlmListener.NOP : listener;
        if (cfg.baseUrl == null || cfg.baseUrl.trim().isEmpty())
            throw new LlmException(LlmException.Kind.CONFIG, 0, "Не указан адрес API", "baseUrl is empty", 0, null, null);
        if (req.model == null || req.model.trim().isEmpty())
            throw new LlmException(LlmException.Kind.CONFIG, 0, "Не выбрана модель", "model is empty", 0, null, null);
        Set<String> drops = new HashSet<>(memory.rejected(cfg.providerId, req.model));
        List<String> dropped = new ArrayList<>();
        int attempt = 0, adaptive = 0;
        while (true) {
            attempt++;
            Progress prog = new Progress();
            final Boolean eo = echoOverride;
            final boolean echoUsed = eo != null ? eo : cfg.quirks.echoReasoning;
            try {
                LlmResult r = once(cfg, req, l, cancel, drops, prog);
                r.attempts = attempt;
                r.droppedParams = dropped;
                return r;
            } catch (LlmException e) {
                if (cancel.isCancelled() || e.kind == LlmException.Kind.CANCELLED) throw LlmException.cancelled();
                if (e.kind == LlmException.Kind.BAD_REQUEST && adaptive < 4 && !prog.delivered && adaptDrops(e, drops, dropped, echoUsed)) {
                    adaptive++;
                    attempt--;
                    String last = dropped.get(dropped.size() - 1);
                    if (!last.contains("→")) learnRejected(cfg, req, last);
                    journal.log(Journal.Cat.LLM, Journal.Level.WARN, "Провайдер отклонил параметр, повтор без него: " + dropped, null, null, -1);
                    continue;
                }
                if (e.retryable() && !prog.delivered && attempt <= cfg.maxRetries
                    && !(e.kind == LlmException.Kind.RATE_LIMIT && e.retryAfterMs > 30_000)) {
                    long delay = e.retryAfterMs > 0 ? Math.min(e.retryAfterMs, 20_000) : Math.min(8_000, 700L << (attempt - 1)) + (long) (Math.random() * 300);
                    l.onRetry(attempt, e.userMessage, delay);
                    journal.log(Journal.Cat.LLM, Journal.Level.WARN, "Повтор запроса #" + attempt + " через " + delay + " мс: " + e.userMessage, null, null, -1);
                    if (!cancel.sleep(delay)) throw LlmException.cancelled();
                    continue;
                }
                e.partial = prog.delivered;
                throw e;
            }
        }
    }

    private void learnRejected(LlmConfig cfg, LlmRequest req, String param) {
        if (!memory.learn(cfg.providerId, req.model, param)) return;
        JSONObject d = new JSONObject();
        J.put(d, "provider", cfg.providerId);
        J.put(d, "model", req.model);
        J.put(d, "param", param);
        journal.log(Journal.Cat.LLM, Journal.Level.WARN, "параметр " + param + " не поддерживается моделью " + req.model + ": больше не отправляю", null, d, -1);
    }

    private LlmResult once(LlmConfig cfg, LlmRequest req, LlmListener l, CancelToken cancel, Set<String> drops, Progress prog)
            throws LlmException {
        final long t0 = System.nanoTime();
        final Acc acc = new Acc(l, prog, t0);
        HttpURLConnection c = null;
        Runnable unhook = null;
        try {
            cancel.throwIfCancelled();
            Boolean eo = echoOverride;
            byte[] payload = buildBody(cfg, req, drops, swapMaxTokensKey, eo != null ? eo : cfg.quirks.echoReasoning).toString().getBytes(StandardCharsets.UTF_8);
            Proxy proxy = Http.parseProxy(cfg.proxy);
            c = Http.open(cfg.url("/chat/completions"), "POST", proxy, cfg.connectTimeoutMs, cfg.idleTimeoutMs);
            final HttpURLConnection fc = c;
            unhook = cancel.onCancel(new Runnable() { @Override public void run() { fc.disconnect(); } });
            if (cfg.apiKey != null && !cfg.apiKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + cfg.apiKey);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", req.stream ? "text/event-stream" : "application/json");
            c.setRequestProperty("Accept-Encoding", "identity"); // gzip would be buffered and break streaming
            for (Map.Entry<String, String> h : cfg.headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = c.getOutputStream()) { os.write(payload); }

            final int status = c.getResponseCode();
            acc.res.httpStatus = status;
            acc.res.headersMs = ms(t0);
            acc.res.responseId = c.getHeaderField("x-request-id") != null ? c.getHeaderField("x-request-id") : "";
            if (status < 200 || status >= 300) {
                String err = Http.readText(c.getErrorStream(), 32 * 1024);
                throw classify(status, c.getHeaderField("Retry-After"), c.getHeaderField("Server"), err);
            }
            String ct = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
            InputStream in = c.getInputStream();
            if (req.stream && !ct.contains("application/json")) {
                SseReader.read(in, new SseReader.Handler() {
                    @Override public boolean onEvent(String event, String data) throws Exception {
                        String d = data.trim();
                        if (d.isEmpty()) return true;
                        if ("[DONE]".equals(d)) return false;
                        JSONObject j = J.parseOrNull(d);
                        if (j == null) return true; // tolerate non-JSON keep-alives
                        acc.chunk(j, status);
                        return true;
                    }
                });
            } else {
                acc.full(Http.readText(in, 16 * 1024 * 1024), status);
            }
            return acc.finish(ms(t0));
        } catch (LlmException e) {
            throw e;
        } catch (CancelToken.CancelledException e) {
            throw LlmException.cancelled();
        } catch (IOException e) {
            if (cancel.isCancelled()) throw LlmException.cancelled();
            throw classifyIo(e);
        } catch (Exception e) {
            if (cancel.isCancelled()) throw LlmException.cancelled();
            throw new LlmException(LlmException.Kind.PROTOCOL, 0, "Неожиданный ответ провайдера", e.getClass().getSimpleName() + ": " + e.getMessage(), 0, null, e);
        } finally {
            if (unhook != null) unhook.run();
            if (c != null) c.disconnect();
        }
    }

    // --------------------------------------------------------------------------------------- request body
    public static JSONObject buildBody(LlmConfig cfg, LlmRequest req, Set<String> drops, boolean swapMaxTokensKey) {
        return buildBody(cfg, req, drops, swapMaxTokensKey, cfg.quirks.echoReasoning);
    }

    public static JSONObject buildBody(LlmConfig cfg, LlmRequest req, Set<String> drops, boolean swapMaxTokensKey, boolean echoReasoning) {
        LlmConfig.Quirks q = cfg.quirks;
        JSONObject b = new JSONObject();
        J.put(b, "model", req.model);
        JSONArray msgs = new JSONArray();
        for (ChatMessage m : req.messages) msgs.put(m.toJson(q.emptyContentAsString, q.toolMessageName, echoReasoning));
        if (q.cacheControl && req.model != null && req.model.startsWith("anthropic/")) markCacheBreakpoints(msgs, req.cacheBreakpoint);
        J.put(b, "messages", msgs);
        if (!req.tools.isEmpty()) {
            JSONArray ts = new JSONArray();
            boolean strict = q.strictTools && !drops.contains("strict");
            for (ToolSpec t : req.tools) ts.put(t.toJson(strict));
            J.put(b, "tools", ts);
            if (req.toolChoice != null && !drops.contains("tool_choice")) J.put(b, "tool_choice", req.toolChoice);
        }
        if (req.temperature != null && !drops.contains("temperature")) {
            double t = Math.max(q.tempMin, Math.min(q.tempMax, req.temperature));
            J.put(b, "temperature", t);
        }
        if (req.maxTokens != null && !drops.contains("max_tokens")) {
            boolean alt = q.maxCompletionTokens ^ swapMaxTokensKey;
            J.put(b, alt ? "max_completion_tokens" : "max_tokens", req.maxTokens);
        }
        applyReasoning(b, q, req.reasoning, drops);
        if (req.realtime && q.sendRealtime && !drops.contains("realtime")) J.put(b, "realtime", true);
        J.put(b, "stream", req.stream);
        if (req.stream && q.includeStreamUsage && !drops.contains("stream_options"))
            J.put(b, "stream_options", J.put(new JSONObject(), "include_usage", true));
        if (req.extra != null) {
            java.util.Iterator<String> it = req.extra.keys();
            while (it.hasNext()) { String k = it.next(); J.put(b, k, req.extra.opt(k)); }
        }
        return b;
    }

    /**
     * Anthropic prompt caching through OpenRouter: marks the system message (covers tools + system) and the last stable history message
     * with {"cache_control":{"type":"ephemeral"}} by turning their string content into a one-element text-part array.
     * Only messages with non-empty plain-text content are eligible; providers that ignore the field are never sent it (see Quirks.cacheControl).
     */
    static void markCacheBreakpoints(JSONArray msgs, int stableIndex) {
        if (msgs.length() > 0) markCache(msgs.optJSONObject(0));
        for (int i = Math.min(stableIndex, msgs.length() - 1); i >= 1; i--) {
            if (markCache(msgs.optJSONObject(i))) break;
        }
    }

    private static boolean markCache(JSONObject m) {
        if (m == null) return false;
        Object c = m.opt("content");
        if (!(c instanceof String) || ((String) c).isEmpty()) return false;
        JSONObject part = new JSONObject();
        J.put(part, "type", "text");
        J.put(part, "text", c);
        J.put(part, "cache_control", J.put(new JSONObject(), "type", "ephemeral"));
        J.put(m, "content", new JSONArray().put(part));
        return true;
    }

    /** Maps the neutral level ("off"/"instant"/"low"/"medium"/"high") onto the provider's own switch. */
    static void applyReasoning(JSONObject b, LlmConfig.Quirks q, String level, Set<String> drops) {
        if (level == null || level.isEmpty()) return;
        boolean off = level.equals("off");
        switch (q.reasoningStyle) {
            case EFFORT:
                if (!drops.contains("reasoning_effort")) J.put(b, "reasoning_effort", off ? q.effortOffValue : level);
                break;
            case THINKING_OBJECT:
                if (!drops.contains("thinking")) J.put(b, "thinking", J.put(new JSONObject(), "type", off ? "disabled" : "enabled"));
                if (q.sendEffort && !off && !drops.contains("reasoning_effort")) J.put(b, "reasoning_effort", level);
                break;
            case ENABLE_THINKING:
                if (!drops.contains("enable_thinking")) J.put(b, "enable_thinking", !off);
                break;
            case OPENROUTER:
                if (!drops.contains("reasoning"))
                    J.put(b, "reasoning", off ? J.put(new JSONObject(), "enabled", false) : J.put(new JSONObject(), "effort", level.equals("instant") ? "low" : level));
                break;
            default:
                break;
        }
    }

    private boolean adaptDrops(LlmException e, Set<String> drops, List<String> dropped, boolean echoUsed) {
        String hay = ((e.param == null ? "" : e.param) + " " + e.technical).toLowerCase(Locale.ROOT);
        if (hay.contains("reasoning_content") && dropped.size() < 6) {
            // The provider either demands the reasoning back (DeepSeek/GLM thinking+tools) or refuses it: flip once and learn.
            echoOverride = !echoUsed;
            dropped.add("reasoning_content→" + (!echoUsed ? "echo" : "no-echo"));
            return true;
        }
        if (hay.contains("max_completion_tokens") || hay.contains("max_tokens")) {
            if (!swapMaxTokensKey) { swapMaxTokensKey = true; dropped.add("max_tokens→swap"); return true; }
            if (drops.add("max_tokens")) { dropped.add("max_tokens"); return true; }
            return false;
        }
        String[] optional = {"realtime", "reasoning_effort", "enable_thinking", "thinking", "reasoning", "stream_options", "tool_choice", "strict", "temperature"};
        for (String k : optional) {
            if (hay.contains(k) && drops.add(k)) { dropped.add(k); return true; }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------- stream accumulator
    private static final class Acc {
        final LlmListener l; final Progress prog; final long t0; final LlmResult res = new LlmResult();
        final StringBuilder text = new StringBuilder(), reasoning = new StringBuilder();
        final TreeMap<Integer, TcBuilder> tcs = new TreeMap<>();
        int anon;

        Acc(LlmListener l, Progress p, long t0) { this.l = l; this.prog = p; this.t0 = t0; }

        void mark() { prog.delivered = true; if (res.firstDeltaMs < 0) res.firstDeltaMs = ms(t0); }

        void chunk(JSONObject j, int status) throws LlmException {
            JSONObject err = J.obj(j, "error");
            if (err != null && !j.has("choices")) throw classify(status >= 400 ? status : 500, null, null, j.toString());
            meta(j);
            JSONArray choices = J.arr(j, "choices");
            if (choices != null && choices.length() > 0) {
                JSONObject ch = choices.optJSONObject(0);
                if (ch != null) {
                    JSONObject delta = J.obj(ch, "delta");
                    if (delta == null) delta = J.obj(ch, "message");
                    if (delta != null) delta(delta);
                    String fin = J.str(ch, "finish_reason");
                    if (!fin.isEmpty()) res.finishReason = fin;
                }
            }
        }

        void meta(JSONObject j) {
            String id = J.str(j, "id");
            if (!id.isEmpty() && res.responseId.isEmpty()) res.responseId = id;
            String model = J.str(j, "model");
            if (!model.isEmpty()) res.model = model;
            String w = J.str(j, "warning");
            if (!w.isEmpty()) res.warning = w;
            JSONObject u = J.obj(j, "usage");
            if (u != null) {
                res.promptTokens = J.num(u, "prompt_tokens", res.promptTokens);
                res.completionTokens = J.num(u, "completion_tokens", res.completionTokens);
                int rt = J.num(u, "reasoning_tokens", -1);
                if (rt < 0) rt = J.num(J.obj(u, "completion_tokens_details"), "reasoning_tokens", -1);
                if (rt >= 0) res.reasoningTokens = rt;
                int ct = J.num(u, "cached_input_tokens", -1);
                if (ct < 0) ct = J.num(J.obj(u, "prompt_tokens_details"), "cached_tokens", -1);
                if (ct >= 0) res.cachedTokens = ct;
            }
        }

        void delta(JSONObject d) {
            String t = contentText(d.opt("content"));
            if (!t.isEmpty()) { mark(); text.append(t); l.onTextDelta(t); }
            String r = J.str(d, "reasoning_content");
            if (r.isEmpty()) r = J.str(d, "reasoning");
            if (!r.isEmpty()) { mark(); reasoning.append(r); l.onReasoningDelta(r); }
            JSONArray arr = J.arr(d, "tool_calls");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject tc = arr.optJSONObject(i);
                if (tc == null) continue;
                int idx = tc.has("index") && !tc.isNull("index") ? tc.optInt("index", i) : i;
                String id = J.str(tc, "id");
                JSONObject fn = J.obj(tc, "function");
                String nm = fn == null ? "" : J.str(fn, "name");
                Object ao = fn == null ? null : fn.opt("arguments");
                String ar = ao == null || ao == JSONObject.NULL ? "" : (ao instanceof String ? (String) ao : ao.toString());
                TcBuilder b = tcs.get(idx);
                if (b != null && !id.isEmpty() && !b.id.isEmpty() && !id.equals(b.id)) { // index reused for a different call
                    idx = (tcs.isEmpty() ? 0 : tcs.lastKey() + 1);
                    b = null;
                }
                if (b == null) { b = new TcBuilder(); tcs.put(idx, b); }
                if (!id.isEmpty() && b.id.isEmpty()) b.id = id;
                if (!nm.isEmpty()) {
                    if (b.name.isEmpty()) b.name = nm;
                    else if (b.args.length() == 0 && !nm.equals(b.name)) b.name += nm; // providers that fragment the name
                }
                if (!ar.isEmpty()) b.args.append(ar);
                mark();
                l.onToolCallDelta(idx, b.id, b.name, ar);
            }
        }

        void full(String body, int status) throws LlmException {
            JSONObject j = J.parseOrNull(body);
            if (j == null) throw new LlmException(LlmException.Kind.PROTOCOL, status, "Ответ провайдера не является JSON", snippet(body), 0, null, null);
            if (J.obj(j, "error") != null && !j.has("choices")) throw classify(status >= 400 ? status : 500, null, null, body);
            meta(j);
            JSONArray choices = J.arr(j, "choices");
            if (choices == null || choices.length() == 0)
                throw new LlmException(LlmException.Kind.PROTOCOL, status, "В ответе нет choices", snippet(body), 0, null, null);
            JSONObject ch = choices.optJSONObject(0);
            JSONObject msg = J.obj(ch, "message");
            if (msg != null) delta(msg);
            res.finishReason = J.str(ch, "finish_reason");
        }

        LlmResult finish(long totalMs) throws LlmException {
            res.totalMs = totalMs;
            res.content = text.toString();
            res.reasoning = reasoning.toString();
            int n = 0;
            for (TcBuilder b : tcs.values()) {
                String id = b.id.isEmpty() ? "call_" + Long.toString(System.nanoTime(), 36) + "_" + (n++) : b.id;
                if (b.name.isEmpty()) continue;
                res.toolCalls.add(new ToolCall(id, b.name, b.args.toString()));
            }
            if (res.content.isEmpty() && res.toolCalls.isEmpty() && res.reasoning.isEmpty() && !"length".equals(res.finishReason)) {
                LlmException empty = new LlmException(LlmException.Kind.PROTOCOL, res.httpStatus, "Модель вернула пустой ответ",
                    "finish_reason=" + res.finishReason, 0, null, null);
                empty.emptyResponse = true;
                throw empty;
            }
            if (res.finishReason.isEmpty()) res.finishReason = res.toolCalls.isEmpty() ? "stop" : "tool_calls";
            return res;
        }
    }

    private static final class TcBuilder { String id = "", name = ""; final StringBuilder args = new StringBuilder(); }

    static String contentText(Object c) {
        if (c == null || c == JSONObject.NULL) return "";
        if (c instanceof String) return (String) c;
        if (c instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray a = (JSONArray) c;
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p != null && ("text".equals(J.str(p, "type")) || p.has("text"))) sb.append(J.str(p, "text"));
            }
            return sb.toString();
        }
        return "";
    }

    // --------------------------------------------------------------------------------------------- models
    public List<ModelInfo> listModels(LlmConfig cfg, CancelToken cancel) throws LlmException {
        return parseModels(getJson(cfg, "/models", cancel));
    }

    /** Authenticated GET of {base}{path}; returns the body or the classified error. */
    public String getJson(LlmConfig cfg, String path, CancelToken cancel) throws LlmException {
        HttpURLConnection c = null;
        Runnable unhook = null;
        try {
            c = Http.open(cfg.url(path), "GET", Http.parseProxy(cfg.proxy), cfg.connectTimeoutMs, Math.min(cfg.idleTimeoutMs, 30_000));
            final HttpURLConnection fc = c;
            unhook = cancel.onCancel(new Runnable() { @Override public void run() { fc.disconnect(); } });
            if (cfg.apiKey != null && !cfg.apiKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + cfg.apiKey);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Accept-Encoding", "identity");
            for (Map.Entry<String, String> h : cfg.headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            int status = c.getResponseCode();
            if (status < 200 || status >= 300)
                throw classify(status, c.getHeaderField("Retry-After"), c.getHeaderField("Server"), Http.readText(c.getErrorStream(), 32 * 1024));
            return Http.readText(c.getInputStream(), 8 * 1024 * 1024);
        } catch (LlmException e) {
            throw e;
        } catch (IOException e) {
            if (cancel.isCancelled()) throw LlmException.cancelled();
            throw classifyIo(e);
        } finally {
            if (unhook != null) unhook.run();
            if (c != null) c.disconnect();
        }
    }

    public static List<ModelInfo> parseModels(String body) throws LlmException {
        List<ModelInfo> out = new ArrayList<>();
        Object root;
        try { root = new org.json.JSONTokener(body).nextValue(); } catch (Exception e) {
            throw new LlmException(LlmException.Kind.PROTOCOL, 200, "Каталог моделей не является JSON", snippet(body), 0, null, e);
        }
        JSONArray arr = null;
        if (root instanceof JSONArray) arr = (JSONArray) root;
        else if (root instanceof JSONObject) {
            arr = J.arr((JSONObject) root, "data");
            if (arr == null) arr = J.arr((JSONObject) root, "models");
        }
        if (arr == null) throw new LlmException(LlmException.Kind.PROTOCOL, 200, "В каталоге моделей нет списка", snippet(body), 0, null, null);
        for (int i = 0; i < arr.length(); i++) {
            Object o = arr.opt(i);
            if (o instanceof String) out.add(new ModelInfo((String) o, null, null, 0));
            else if (o instanceof JSONObject) {
                JSONObject m = (JSONObject) o;
                String id = J.str(m, "id");
                if (id.isEmpty()) id = J.str(m, "name");
                if (id.isEmpty()) continue;
                out.add(new ModelInfo(id, J.str(m, "name"), J.str(m, "owned_by"), ModelCapsParser.parse(m, id)));
            }
        }
        Collections.sort(out, new java.util.Comparator<ModelInfo>() {
            @Override public int compare(ModelInfo a, ModelInfo b) {
                if (a.chatLikely != b.chatLikely) return a.chatLikely ? -1 : 1;
                return a.id.compareToIgnoreCase(b.id);
            }
        });
        return out;
    }

    // ------------------------------------------------------------------------------------------- errors
    static long ms(long t0) { return (System.nanoTime() - t0) / 1_000_000L; }

    static String snippet(String s) {
        if (s == null) return "";
        String t = s.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        return t.length() > 400 ? t.substring(0, 400) + "…" : t;
    }

    static boolean isGeoBlock(int status, String lowBody, String serverHdr) {
        if (status != 403 && status != 451 && status != 401) return false;
        return lowBody.contains("block access from your country") || lowBody.contains("unsupported_country_region_territory")
            || lowBody.contains("not available in your country") || lowBody.contains("not available in your region")
            || lowBody.contains("access from your country") || status == 451
            || (serverHdr != null && serverHdr.toLowerCase(Locale.ROOT).contains("cloudfront") && lowBody.contains("country"));
    }

    public static LlmException classify(int status, String retryAfter, String serverHdr, String body) {
        String b = body == null ? "" : body;
        String low = b.toLowerCase(Locale.ROOT);
        JSONObject j = J.parseOrNull(b);
        JSONObject err = j == null ? null : J.obj(j, "error");
        if (err == null && j != null && j.has("message")) err = j;
        String msg = err == null ? "" : J.str(err, "message");
        String code = err == null ? "" : J.str(err, "code");
        String param = err == null ? null : (J.str(err, "param", "").isEmpty() ? null : J.str(err, "param"));
        String detail = msg.isEmpty() ? snippet(b) : msg + (code.isEmpty() ? "" : " [" + code + "]");
        String tech = "HTTP " + status + (serverHdr == null ? "" : " (" + serverHdr + ")") + ": " + detail;
        long ra = parseRetryAfter(retryAfter);
        LlmException.Kind k;
        String user;
        if (isGeoBlock(status, low, serverHdr)) {
            k = LlmException.Kind.GEO_BLOCK;
            user = "Провайдер блокирует доступ из вашей страны или сети. Включите VPN, укажите прокси или другой адрес API в настройках LLM";
        } else if (status == 401) {
            k = LlmException.Kind.AUTH; user = "Неверный, просроченный или отозванный API-ключ";
        } else if (status == 402 || low.contains("insufficient_quota") || low.contains("insufficient balance") || (status == 429 && low.contains("quota") && low.contains("exceed"))) {
            k = LlmException.Kind.PAYMENT; user = "Нет средств или исчерпана квота провайдера";
        } else if (status == 403) {
            k = LlmException.Kind.AUTH; user = "Доступ запрещён: у ключа нет прав или он не подходит для этой модели";
        } else if (status == 404) {
            k = LlmException.Kind.NOT_FOUND; user = "Модель или адрес API не найдены";
        } else if (status == 408) {
            k = LlmException.Kind.TIMEOUT; user = "Сервер не дождался запроса";
        } else if (status == 429) {
            k = LlmException.Kind.RATE_LIMIT; user = "Слишком много запросов к провайдеру (лимит скорости)";
        } else if (status == 413 || code.equals("context_length_exceeded")
            || ((status == 400 || status == 422) && (low.contains("context length") || low.contains("context_length") || low.contains("maximum context")
                || (low.contains("too long") && low.contains("token")) || low.contains("reduce the length")))) {
            k = LlmException.Kind.CONTEXT_LENGTH; user = "Запрос не помещается в контекст модели";
        } else if (status >= 500) {
            k = LlmException.Kind.SERVER; user = status == 503 ? "Сервер провайдера перегружен" : "Ошибка на стороне провайдера";
        } else {
            k = LlmException.Kind.BAD_REQUEST; user = "Провайдер отклонил запрос" + (msg.isEmpty() ? "" : ": " + (msg.length() > 160 ? msg.substring(0, 160) + "…" : msg));
        }
        return new LlmException(k, status, user, tech, ra, param, null);
    }

    static LlmException classifyIo(IOException e) {
        String m = String.valueOf(e.getMessage());
        String low = m.toLowerCase(Locale.ROOT);
        String tech = e.getClass().getSimpleName() + ": " + m;
        if (low.contains("authentication challenge"))
            return new LlmException(LlmException.Kind.AUTH, 401, "Неверный или просроченный API-ключ", tech, 0, null, e);
        if (e instanceof UnknownHostException)
            return new LlmException(LlmException.Kind.NETWORK, 0, "Не удаётся найти сервер: нет интернета или DNS", tech, 0, null, e);
        if (e instanceof SocketTimeoutException)
            return new LlmException(LlmException.Kind.TIMEOUT, 0, "Сервер не ответил вовремя", tech, 0, null, e);
        if (e instanceof SSLException)
            return new LlmException(LlmException.Kind.TLS, 0, "Ошибка защищённого соединения (проверьте дату и время, прокси, сертификаты)", tech, 0, null, e);
        if (low.contains("proxy"))
            return new LlmException(LlmException.Kind.NETWORK, 0, "Прокси недоступен или отклонил соединение", tech, 0, null, e);
        if (e instanceof ConnectException || e instanceof NoRouteToHostException)
            return new LlmException(LlmException.Kind.NETWORK, 0, "Нет соединения с сервером", tech, 0, null, e);
        if (e instanceof EOFException || low.contains("reset") || low.contains("closed") || low.contains("broken pipe") || low.contains("unexpected end"))
            return new LlmException(LlmException.Kind.NETWORK, 0, "Соединение с сервером прервано", tech, 0, null, e);
        return new LlmException(LlmException.Kind.NETWORK, 0, "Сетевая ошибка", tech, 0, null, e);
    }

    static long parseRetryAfter(String h) {
        if (h == null || h.trim().isEmpty()) return 0;
        try { return (long) (Double.parseDouble(h.trim()) * 1000); } catch (NumberFormatException ignored) { }
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            long d = f.parse(h.trim()).getTime() - System.currentTimeMillis();
            return Math.max(0, d);
        } catch (Exception e) { return 0; }
    }
}
