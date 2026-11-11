# SMS Gora Java / Kotlin client

[![](https://jitpack.io/v/tekservixhq/smsgora-java.svg)](https://jitpack.io/#tekservixhq/smsgora-java)

**SMS Gora SMS Gateway** - official Java and Kotlin client: send and receive SMS through your own Android phones. By Tekservix.

Java and Kotlin client for the [SMS Gora SMS Gateway](https://tekservix.com/smsgora/) API.

SMS Gora turns Android phones with ordinary SIM cards into an SMS gateway. Your server calls the API, a linked phone sends the text, and replies and delivery reports come back to you. There's no sender ID registration and no per-message fee from us: your mobile operator bills the texts like any SMS.

- Java 8+, works from Kotlin, Spring Boot, Ktor, Quarkus and plain Java
- One small dependency (Gson)
- Webhook signature check built in

## Maven Central

Gradle (Kotlin DSL):

```kotlin
implementation("com.tekservix:smsgora:1.0.2")
```

Maven:

```xml
<dependency>
  <groupId>com.tekservix</groupId>
  <artifactId>smsgora</artifactId>
  <version>1.0.2</version>
</dependency>
```

The JitPack coordinates below keep working.

## Install

Add the JitPack repository, then the dependency.

**Gradle (Kotlin DSL)**

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.tekservixhq:smsgora-java:v1.0.1")
}
```

**Gradle (Groovy)**

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.tekservixhq:smsgora-java:v1.0.1'
}
```

**Maven**

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.tekservixhq</groupId>
  <artifactId>smsgora-java</artifactId>
  <version>v1.0.1</version>
</dependency>
```

## Quick start

Create an API key in the SMS Gora dashboard (SMS Gora -> API keys) and keep it on your server.

```java
import com.tekservix.smsgora.SmsGora;

SmsGora sms = new SmsGora(System.getenv("SMSGORA_KEY"));

JsonElement result = sms.sendSms("+15551234567", "Your verification code is 482913");
System.out.println(result.getAsJsonObject().get("message_ids")); // [1873]
```

Kotlin:

```kotlin
val sms = SmsGora(System.getenv("SMSGORA_KEY"))
sms.sendSms("+15551234567", "Your verification code is 482913")
```

## Sending

```java
// Several recipients (up to 1,000), from phone 42, SIM 2, scheduled for later
sms.sendSms(
    Arrays.asList("+15551234567", "+447700900123"),
    "Reminder: your appointment is tomorrow at 10:00",
    42L,                     // deviceId, or null for your best online phone
    1,                       // simSlot: 0 = SIM 1, 1 = SIM 2, or null
    "2026-12-01T08:00:00Z"   // scheduleAt, or null to send now
);

// Different texts for different people in one call (up to 5,000 recipients)
Map<String, Object> aline = new HashMap<>();
aline.put("recipients", Collections.singletonList("+15551234567"));
aline.put("message", "Hi Aline, parcel #1042 is ready.");
sms.sendBulkSms(42, Collections.singletonList(aline), "Hello, your parcel is ready for pickup.");
```

## Delivery status and incoming SMS

```java
JsonObject msg = sms.getMessage(1873).getAsJsonObject();
System.out.println(msg.get("status")); // queued -> dispatched -> sent -> delivered (or failed)

sms.cancelMessage(1874); // works while queued or scheduled

Map<String, Object> filters = new HashMap<>();
filters.put("since", "2026-11-01");
filters.put("limit", 50);
JsonElement inbox = sms.listReceivedSms(null, filters);
JsonElement phones = sms.listDevices();
JsonElement account = sms.getAccount();
```

Anything else in the [API reference](https://tekservix.com/smsgora/docs/) is one call away:

```java
JsonElement batch = sms.request("GET", "/batches/311", null);
```

## Webhooks

Add your HTTPS URL in SMS Gora -> Webhooks (or with `createWebhook`) and keep the secret. Each request carries `X-SmsGora-Signature: sha256=...`, an HMAC-SHA256 of the raw body. Spring Boot example:

```java
@PostMapping("/sms/webhook")
public ResponseEntity<Void> smsWebhook(@RequestBody byte[] raw,
                                       @RequestHeader("X-SmsGora-Signature") String signature) {
    if (!SmsGora.verifyWebhook(raw, signature, System.getenv("SMSGORA_WEBHOOK_SECRET"))) {
        return ResponseEntity.status(401).build();
    }
    JsonObject event = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
    if ("sms.received".equals(event.get("event").getAsString())) {
        JsonObject data = event.getAsJsonObject("data");
        log.info("{}: {}", data.get("from"), data.get("message"));
    }
    return ResponseEntity.ok().build(); // answer within 10 seconds; queue slow work
}
```

## Errors

Failed calls throw `SmsGoraException` (unchecked) with `getStatus()`, `getMessage()`, `getErrors()` and, on 429, `getRetryAfter()` in seconds.

```java
try {
    sms.sendSms("+15551234567", "Hi");
} catch (SmsGoraException e) {
    if (e.getStatus() == 429) {
        // rate limit (300 requests/minute per key) or plan limit reached
    }
}
```

**Android apps:** don't put the API key inside an APK, where anyone can extract it. Have the app call your own backend, and call SMS Gora from there.

## Migrating from TextBee

SMS Gora accepts TextBee-style requests, so most integrations switch by changing the base URL and key. See the [compatibility notes](https://tekservix.com/smsgora/docs/).

## More from Tekservix

- [WAservix WhatsApp API Gateway](https://tekservix.com/waservix/): send WhatsApp messages from your own number ([waservix-java](https://github.com/tekservixhq/waservix-java))
- [AI Sales Agent](https://tekservix.com/ai-sales-agent): answers customers on WhatsApp and SMS using your website's content
- [Website to App](https://tekservix.com/mobile-apps): turn your website into Android and iOS apps
- SMS Gora is also available for Node.js (`npm install smsgora`), Python (`pip install smsgora`), PHP (`composer require tekservix/smsgora`) and Go (`go get github.com/tekservixhq/smsgora-go`)

## License

MIT
