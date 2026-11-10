package com.tekservix.smsgora;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class SmsGoraTest {
    static HttpServer server;
    static String lastMethod, lastPath, lastKey;
    static JsonObject lastBody;
    static SmsGora client;

    @BeforeClass
    public static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            lastMethod = ex.getRequestMethod();
            lastPath = ex.getRequestURI().toString();
            lastKey = ex.getRequestHeaders().getFirst("x-api-key");
            String body = read(ex.getRequestBody());
            lastBody = body.isEmpty() ? null : JsonParser.parseString(body).getAsJsonObject();
            int status = 200;
            String reply = "{\"success\":true,\"data\":{\"message_ids\":[1873]}}";
            if (lastPath.startsWith("/limit")) {
                status = 429;
                ex.getResponseHeaders().add("Retry-After", "7");
                reply = "{\"success\":false,\"message\":\"Too many requests\"}";
            }
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
        server.start();
        client = new SmsGora("sgk_test", "http://127.0.0.1:" + server.getAddress().getPort() + "/", 5000);
    }

    @AfterClass
    public static void stop() { server.stop(0); }

    static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void sendSms() {
        JsonElement res = client.sendSms("+1555", "hi");
        assertEquals(1873, res.getAsJsonObject().getAsJsonArray("message_ids").get(0).getAsInt());
        assertEquals("POST", lastMethod);
        assertEquals("/send-sms", lastPath);
        assertEquals("sgk_test", lastKey);
        assertEquals("+1555", lastBody.getAsJsonArray("recipients").get(0).getAsString());
        assertEquals("hi", lastBody.get("message").getAsString());
    }

    @Test
    public void sendFromDevice() {
        client.sendSms(SmsGora.list("+1", "+2"), "x", 42L, 1, "2026-12-01T08:00:00Z");
        assertEquals("/devices/42/send-sms", lastPath);
        assertEquals(1, lastBody.get("sim_slot").getAsInt());
        assertEquals("2026-12-01T08:00:00Z", lastBody.get("schedule_at").getAsString());
    }

    @Test
    public void pathsAndQueries() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("limit", 5);
        f.put("since", null);
        client.listReceivedSms(3L, f);
        assertEquals("/devices/3/get-received-sms?limit=5", lastPath);
        client.listMessages(Collections.singletonMap("status", "sent"));
        assertEquals("/messages?status=sent", lastPath);
        client.deleteWebhook(9);
        assertEquals("DELETE", lastMethod);
        assertEquals("/webhooks/9", lastPath);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recipients", SmsGora.list("+1"));
        client.sendBulkSms(42, Collections.singletonList(m), "t");
        assertEquals("/devices/42/send-bulk-sms", lastPath);
        assertEquals("t", lastBody.get("messageTemplate").getAsString());
    }

    @Test
    public void errors() {
        SmsGora c = new SmsGora("k", "http://127.0.0.1:" + server.getAddress().getPort() + "/limit", 5000);
        try {
            c.getAccount();
            fail("expected exception");
        } catch (SmsGoraException e) {
            assertEquals(429, e.getStatus());
            assertEquals(Double.valueOf(7), e.getRetryAfter());
            assertEquals("Too many requests", e.getMessage());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void requiresKey() { new SmsGora(""); }

    @Test
    public void webhook() throws Exception {
        String raw = "{\"event\":\"sms.received\"}";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("s3cret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder sig = new StringBuilder("sha256=");
        for (byte b : mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))) sig.append(String.format("%02x", b));
        assertTrue(SmsGora.verifyWebhook(raw, sig.toString(), "s3cret"));
        assertFalse(SmsGora.verifyWebhook(raw, sig.toString(), "nope"));
        assertFalse(SmsGora.verifyWebhook(raw, null, "s3cret"));
    }
}
