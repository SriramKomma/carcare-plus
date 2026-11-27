package ca.carcareplus.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.ServiceUnavailableException;

/** Talks to a pretend Twilio on this computer: no real SMS, no account needed. */
class TwilioSmsGatewayTest {

    static final String WEBHOOK = "https://abc.ngrok-free.app/api/sms/incoming";

    /** One request the pretend Twilio received. */
    record Seen(String path, String authorization, Map<String, String> form) {
    }

    private HttpServer fakeTwilio;
    private final List<Seen> seen = new ArrayList<>();
    private int status = 201;
    private String reply = "{\"sid\":\"SM1\"}";

    @BeforeEach
    void startFakeTwilio() throws IOException {
        fakeTwilio = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fakeTwilio.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = new HashMap<>();
            for (String pair : body.split("&")) {
                String[] parts = pair.split("=", 2);
                form.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            synchronized (seen) {
                seen.add(new Seen(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization"), form));
            }
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        fakeTwilio.start();
    }

    @AfterEach
    void stopFakeTwilio() {
        fakeTwilio.stop(0);
    }

    private TwilioSmsGateway gateway() {
        return new TwilioSmsGateway("AC123", "test-token", "519 555 0199", WEBHOOK,
                "http://localhost:" + fakeTwilio.getAddress().getPort());
    }

    @Test
    void sendPostsToFromAndBodyWithTheAccountLogin() throws Exception {
        gateway().send("(519) 555-0101", "CarCare+ JOB-1: Flat tire. Reply ACCEPTED");
        assertEquals(1, seen.size());
        Seen request = seen.get(0);
        assertEquals("/2010-04-01/Accounts/AC123/Messages.json", request.path());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("AC123:test-token".getBytes(StandardCharsets.UTF_8)),
                request.authorization());
        assertEquals("+15195550101", request.form().get("To"), "Twilio wants +1 and 10 digits");
        assertEquals("+15195550199", request.form().get("From"));
        assertEquals("CarCare+ JOB-1: Flat tire. Reply ACCEPTED", request.form().get("Body"));
    }

    @Test
    void twilioRefusingTheMessageIsReportedWithItsReason() {
        status = 400;
        reply = "{\"code\":21608,\"message\":\"The number is unverified.\"}";
        ServiceUnavailableException e = assertThrows(ServiceUnavailableException.class,
                () -> gateway().send("(519) 555-0102", "hello"));
        assertTrue(e.getMessage().contains("The number is unverified. (code 21608)"), e.getMessage());
    }

    @Test
    void signatureMatchesTwilioRuleComputedIndependently() {
        // Expected value computed separately in Python with hmac/hashlib (address + sorted name/value pairs)
        Map<String, String> form = Map.of("From", "+15195550101", "Body", "ACCEPTED", "MessageSid", "SM123", "To", "+15195550199");
        assertEquals("Yz++871LGhgly+ee/iKIIVX49WE=", TwilioSmsGateway.sign("test-token", WEBHOOK, form));
        assertTrue(gateway().isGenuine("Yz++871LGhgly+ee/iKIIVX49WE=", form));
    }

    @Test
    void forgedOrChangedRepliesAreNotBelieved() {
        Map<String, String> form = new HashMap<>(Map.of("From", "+15195550101", "Body", "ACCEPTED"));
        String good = TwilioSmsGateway.sign("test-token", WEBHOOK, form);
        assertTrue(gateway().isGenuine(good, form));
        assertFalse(gateway().isGenuine(null, form), "no signature");
        assertFalse(gateway().isGenuine(TwilioSmsGateway.sign("someone-elses-token", WEBHOOK, form), form), "wrong token");
        form.put("From", "+15195550102");
        assertFalse(gateway().isGenuine(good, form), "someone changed who it is from");
    }

    @Test
    void phoneNumbersAreWrittenTheWayTwilioWantsThem() {
        assertEquals("+15195550102", TwilioSmsGateway.e164("+1 (519) 555-0102"));
        assertEquals("+15195550101", TwilioSmsGateway.e164("5195550101"));
        assertThrows(InvalidInputException.class, () -> TwilioSmsGateway.e164("12345"));
    }

    @Test
    void halfFinishedSetupIsAnError() {
        assertThrows(InvalidInputException.class, () -> new TwilioSmsGateway("AC123", "", "519 555 0199", WEBHOOK, "http://x"));
        assertFalse(gateway().isSimulated());
        assertEquals("Twilio, from +15195550199", gateway().description());
    }
}
