package ca.carcareplus.sms;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.ServiceUnavailableException;
import ca.carcareplus.validation.DataValidator;

/**
 * Real SMS through Twilio's REST API, called with the JDK's own HttpClient (no Twilio
 * library): https://www.twilio.com/docs/messaging/api/message-resource
 *
 * <h2>Sending</h2>
 * POST https://api.twilio.com/2010-04-01/Accounts/{AccountSid}/Messages.json
 * with To, From and Body, logged in with the account id and auth token (HTTP Basic).
 *
 * <h2>Receiving replies</h2>
 * Twilio forwards each reply to our web server (POST /api/sms/incoming) at the public
 * address set for the phone number in the Twilio console, e.g. an ngrok tunnel.
 * Because that address is on the internet, anyone could post a fake "ACCEPTED" to it.
 * So every reply is checked with {@link #isGenuine}: Twilio signs each request with
 * our auth token (HMAC-SHA1 of the address plus the sorted form fields), and only a
 * request carrying the right signature is believed.
 * https://www.twilio.com/docs/usage/webhooks/webhooks-security
 *
 * <h2>Settings (environment variables, never in the code or in git)</h2>
 * <pre>
 * TWILIO_ACCOUNT_SID   ACxxxxxxxx...           (Twilio console)
 * TWILIO_AUTH_TOKEN    the secret token        (Twilio console)
 * TWILIO_FROM_NUMBER   +15195550199            your Twilio phone number
 * TWILIO_WEBHOOK_URL   https://abc.ngrok-free.app/api/sms/incoming
 *                      exactly the address set in the console (needed for the signature check)
 * </pre>
 */
public final class TwilioSmsGateway implements SmsGateway {

    public static final String SID_VARIABLE = "TWILIO_ACCOUNT_SID";
    public static final String TOKEN_VARIABLE = "TWILIO_AUTH_TOKEN";
    public static final String FROM_VARIABLE = "TWILIO_FROM_NUMBER";
    public static final String WEBHOOK_VARIABLE = "TWILIO_WEBHOOK_URL";
    public static final String DEFAULT_API = "https://api.twilio.com";

    private static final Gson GSON = new Gson();

    private final String accountSid;
    private final String authToken;
    private final String fromNumber;
    private final String webhookUrl;
    private final String apiBase;
    private final HttpClient client;

    /**
     * @param fromNumber our Twilio number, any Canadian format
     * @param webhookUrl the public address Twilio posts replies to
     * @param apiBase    DEFAULT_API, or a local test server
     */
    public TwilioSmsGateway(String accountSid, String authToken, String fromNumber, String webhookUrl, String apiBase) {
        if (isBlank(accountSid) || isBlank(authToken) || isBlank(fromNumber) || isBlank(webhookUrl) || isBlank(apiBase)) {
            throw new InvalidInputException("Twilio needs " + SID_VARIABLE + ", " + TOKEN_VARIABLE + ", "
                    + FROM_VARIABLE + " and " + WEBHOOK_VARIABLE + ".");
        }
        this.accountSid = accountSid.trim();
        this.authToken = authToken.trim();
        this.fromNumber = e164(fromNumber);
        this.webhookUrl = webhookUrl.trim();
        this.apiBase = apiBase.trim();
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * @return a gateway from the four TWILIO_ variables, or null if none is set
     * @throws InvalidInputException if only some are set (a half-finished setup should be noticed)
     */
    public static TwilioSmsGateway fromEnvironment() {
        String sid = System.getenv(SID_VARIABLE);
        String token = System.getenv(TOKEN_VARIABLE);
        String from = System.getenv(FROM_VARIABLE);
        String webhook = System.getenv(WEBHOOK_VARIABLE);
        if (isBlank(sid) && isBlank(token) && isBlank(from) && isBlank(webhook)) {
            return null;
        }
        return new TwilioSmsGateway(sid, token, from, webhook, DEFAULT_API);
    }

    @Override
    public void send(String toPhone, String text) throws ServiceUnavailableException {
        String form = "To=" + encode(e164(toPhone)) + "&From=" + encode(fromNumber) + "&Body=" + encode(text);
        String login = Base64.getEncoder().encodeToString((accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(apiBase + "/2010-04-01/Accounts/" + encode(accountSid) + "/Messages.json"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Basic " + login)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ServiceUnavailableException("Could not reach Twilio: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Sending the SMS was interrupted.", e);
        }
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new ServiceUnavailableException("Twilio did not send the SMS to " + toPhone
                    + " (HTTP " + response.statusCode() + "): " + errorMessage(response.body()));
        }
    }

    /**
     * True only if the request really came from Twilio.
     *
     * @param signature the X-Twilio-Signature header
     * @param form      every form field of the request
     */
    public boolean isGenuine(String signature, Map<String, String> form) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        byte[] expected = sign(authToken, webhookUrl, form).getBytes(StandardCharsets.UTF_8);
        // Compares every byte, so the time taken does not tell an attacker how much was right
        return MessageDigest.isEqual(expected, signature.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** Twilio's rule: address + each field name and value, sorted by name; HMAC-SHA1 with the token; Base64. */
    static String sign(String authToken, String url, Map<String, String> form) {
        StringBuilder data = new StringBuilder(url);
        new TreeMap<>(form).forEach((name, value) -> data.append(name).append(value));
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is missing from this Java installation", e);
        }
    }

    @Override
    public boolean isSimulated() {
        return false;
    }

    @Override
    public String description() {
        return "Twilio, from " + fromNumber;
    }

    /** "(519) 555-0100" -> "+15195550100", the form Twilio wants. */
    static String e164(String phone) {
        String normalized = DataValidator.normalizePhone(phone == null ? "" : phone.trim());
        if (normalized == null) {
            throw new InvalidInputException("Not a Canadian phone number: " + phone);
        }
        return "+1" + normalized.replaceAll("\\D", "");
    }

    private static String errorMessage(String body) {
        try {
            JsonObject json = GSON.fromJson(body, JsonObject.class);
            if (json != null && json.has("message")) {
                String code = json.has("code") ? " (code " + json.get("code").getAsString() + ")" : "";
                return json.get("message").getAsString() + code;
            }
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            // not JSON: fall through
        }
        return body == null || body.isBlank() ? "no details" : body.substring(0, Math.min(200, body.length()));
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
