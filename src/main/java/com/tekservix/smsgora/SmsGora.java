package com.tekservix.smsgora;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for the SMS Gora SMS Gateway API.
 *
 * <pre>{@code
 * SmsGora sms = new SmsGora(System.getenv("SMSGORA_KEY"));
 * sms.sendSms("+15551234567", "Your verification code is 482913");
 * }</pre>
 *
 * Docs: https://tekservix.com/smsgora/docs/
 */
public class SmsGora {
    public static final String VERSION = "1.0.4";
    public static final String DEFAULT_BASE_URL = "https://tekservix.com/smsgora/api/v1";

    private static final Gson GSON = new Gson();

    private final String apiKey;
    private final String baseUrl;
    private final int timeoutMs;

    /** @param apiKey your SMS Gora API key (starts with sgk_). Keep it on your server. */
    public SmsGora(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL, 30000);
    }

    public SmsGora(String apiKey, String baseUrl, int timeoutMs) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("SmsGora: apiKey is required (create one in SMS Gora -> API keys)");
        }
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.timeoutMs = timeoutMs;
    }

    /**
     * Call any documented endpoint, e.g. {@code request("GET", "/devices", null)}.
     *
     * @param body a Map, POJO or JsonElement (serialized with Gson), or null
     * @return the {@code data} field of the answer
     */
    public JsonElement request(String method, String path, Object body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "smsgora-java/" + VERSION);
            if (body != null) {
                byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = conn.getResponseCode();
            String text = read(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            JsonElement json = null;
            try {
                json = text.isEmpty() ? null : JsonParser.parseString(text);
            } catch (RuntimeException ignored) {
                // not JSON
            }
            JsonObject obj = json != null && json.isJsonObject() ? json.getAsJsonObject() : null;
            boolean failed = status >= 400
                    || (obj != null && obj.has("success") && obj.get("success").isJsonPrimitive() && !obj.get("success").getAsBoolean());
            if (failed) {
                String msg = obj != null && obj.has("message") && !obj.get("message").isJsonNull()
                        ? obj.get("message").getAsString() : "HTTP " + status;
                String retry = conn.getHeaderField("Retry-After");
                Double retryAfter = null;
                if (retry != null) {
                    try {
                        retryAfter = Double.valueOf(retry.trim());
                    } catch (NumberFormatException ignored) {
                        // HTTP date form, ignore
                    }
                }
                throw new SmsGoraException(msg, status, obj != null ? obj.get("errors") : null, text, retryAfter);
            }
            if (obj != null && obj.has("data")) {
                return obj.get("data");
            }
            return json;
        } catch (IOException e) {
            throw new SmsGoraException("SmsGora: request failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Send an SMS to one number from your best online phone. */
    public JsonElement sendSms(String to, String message) {
        return sendSms(Collections.singletonList(to), message, null, null, null);
    }

    /**
     * Send an SMS to up to 1,000 numbers.
     *
     * @param deviceId   send from this phone, or null for your best online phone
     * @param simSlot    0 = SIM 1, 1 = SIM 2, or null
     * @param scheduleAt ISO 8601 date-time to send later, or null
     */
    public JsonElement sendSms(List<String> to, String message, Long deviceId, Integer simSlot, String scheduleAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipients", to);
        body.put("message", message);
        if (simSlot != null) body.put("sim_slot", simSlot);
        if (scheduleAt != null) body.put("schedule_at", scheduleAt);
        String path = deviceId != null ? "/devices/" + deviceId + "/send-sms" : "/send-sms";
        return request("POST", path, body);
    }

    /**
     * Different texts for different people in one call (up to 5,000 recipients).
     *
     * @param messages list of maps with "recipients" (list) and optional "message"
     */
    public JsonElement sendBulkSms(long deviceId, List<Map<String, Object>> messages, String messageTemplate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", messages);
        if (messageTemplate != null) body.put("messageTemplate", messageTemplate);
        return request("POST", "/devices/" + deviceId + "/send-bulk-sms", body);
    }

    /** Status is queued, dispatched, sent, delivered, failed or cancelled. */
    public JsonElement getMessage(long id) { return request("GET", "/messages/" + id, null); }

    /** Cancel a queued or scheduled message. */
    public JsonElement cancelMessage(long id) { return request("POST", "/messages/" + id + "/cancel", null); }

    /** Filters: direction (in/out), status, device_id, phone, since, after_id, page, limit. */
    public JsonElement listMessages(Map<String, ?> filters) { return request("GET", "/messages" + query(filters), null); }

    /** Incoming SMS for all phones (deviceId null) or one phone. */
    public JsonElement listReceivedSms(Long deviceId, Map<String, ?> filters) {
        String path = deviceId != null ? "/devices/" + deviceId + "/get-received-sms" : "/received-sms";
        return request("GET", path + query(filters), null);
    }

    public JsonElement getBatch(long id) { return request("GET", "/batches/" + id, null); }

    /** Your linked Android phones. */
    public JsonElement listDevices() { return request("GET", "/devices", null); }

    public JsonElement getDevice(long id) { return request("GET", "/devices/" + id, null); }

    /** Account, plan and usage. */
    public JsonElement getAccount() { return request("GET", "/account", null); }

    public JsonElement listWebhooks() { return request("GET", "/webhooks", null); }

    /** Register a webhook for sms.received on all phones. The answer includes the secret (only returned once). */
    public JsonElement createWebhook(String url) { return createWebhook(url, Collections.singletonList("sms.received"), null); }

    public JsonElement createWebhook(String url, List<String> events, Long deviceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("events", events);
        body.put("device_id", deviceId);
        return request("POST", "/webhooks", body);
    }

    public JsonElement deleteWebhook(long id) { return request("DELETE", "/webhooks/" + id, null); }

    /**
     * Check the X-SmsGora-Signature header (sha256=&lt;hex HMAC-SHA256 of the raw body&gt;).
     *
     * @param rawBody the raw request body bytes, before JSON parsing
     */
    public static boolean verifyWebhook(byte[] rawBody, String signature, String secret) {
        if (rawBody == null || rawBody.length == 0 || signature == null || secret == null || secret.isEmpty()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(rawBody);
            StringBuilder hex = new StringBuilder("sha256=");
            for (byte b : digest) hex.append(String.format("%02x", b));
            return MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean verifyWebhook(String rawBody, String signature, String secret) {
        return rawBody != null && verifyWebhook(rawBody.getBytes(StandardCharsets.UTF_8), signature, secret);
    }

    private static String query(Map<String, ?> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        try {
            for (Map.Entry<String, ?> e : params.entrySet()) {
                if (e.getValue() == null || String.valueOf(e.getValue()).isEmpty()) continue;
                sb.append(sb.length() == 0 ? '?' : '&')
                  .append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                  .append(URLEncoder.encode(String.valueOf(e.getValue()), "UTF-8"));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return sb.toString();
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static List<String> list(String... items) { return Arrays.asList(items); }
}
