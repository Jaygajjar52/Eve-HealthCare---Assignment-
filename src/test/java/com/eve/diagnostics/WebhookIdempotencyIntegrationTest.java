package com.eve.diagnostics;

import com.eve.diagnostics.repository.BookingRepository;
import com.eve.diagnostics.repository.PaymentRepository;
import com.eve.diagnostics.repository.WebhookEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The important tests in this project: proving the webhook is idempotent.
 *
 * <p>Two separate guarantees are checked here, because they fail for different
 * reasons. Repeating the same event must change nothing after the first delivery,
 * which is the ordinary provider retry. And delivering the same event
 * <em>concurrently</em> must still produce exactly one processing, which is the
 * case a naive "does this eventId exist?" check gets wrong: two requests both
 * see no row and both carry on. Only a UNIQUE constraint arbitrates that.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebhookIdempotencyIntegrationTest extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private WebhookEventRepository webhookEventRepository;

    @Value("${eve.webhook.secret}")
    private String webhookSecret;

    // --- helpers -----------------------------------------------------------

    /** hex(HMAC-SHA256(secret, rawBody)), exactly what a provider would send. */
    private String sign(String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String webhookBody(String eventId, String paymentId, String bookingId, String status) {
        return """
                {"eventId":"%s","paymentId":"%s","bookingId":"%s","status":"%s","timestamp":"%s"}"""
                .formatted(eventId, paymentId, bookingId, status, Instant.now().toString());
    }

    /** Posts a webhook with a correct signature. */
    private MvcResult sendWebhook(String rawBody) throws Exception {
        return mockMvc.perform(post("/payments/webhook/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", sign(rawBody))
                        .content(rawBody))
                .andReturn();
    }

    private String resultOf(MvcResult result) throws Exception {
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        return body.get("result").asText();
    }

    private String signupToken() throws Exception {
        String email = "hook-" + UUID.randomUUID() + "@eve.com";
        MvcResult signup = mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Hook User","email":"%s","password":"secret123"}""".formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        return JSON.readTree(signup.getResponse().getContentAsString()).get("accessToken").asText();
    }

    /**
     * Books and pays with a forced FAILED outcome, which leaves the payment in
     * the opposite state from the SUCCESS webhook these tests then deliver.
     */
    private Scenario scenarioWithFailedPayment() throws Exception {
        String token = signupToken();
        String bookingBody = """
                {"centreId":"11111111-1111-1111-1111-111111111111",\
                "testId":"a1111111-1111-1111-1111-111111111111",\
                "appointmentTime":"%s"}"""
                .formatted(Instant.now().plus(3, ChronoUnit.DAYS).toString());
        MvcResult booking = mockMvc.perform(post("/bookings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(bookingBody))
                .andExpect(status().isCreated())
                .andReturn();
        String bookingId = JSON.readTree(booking.getResponse().getContentAsString()).get("id").asText();

        MvcResult payment = mockMvc.perform(post("/payments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingId":"%s","simulateStatus":"FAILED"}""".formatted(bookingId)))
                .andExpect(status().isCreated())
                .andReturn();
        String paymentId = JSON.readTree(payment.getResponse().getContentAsString()).get("id").asText();

        assertThat(bookingRepository.findById(UUID.fromString(bookingId)).orElseThrow().getStatus().name())
                .isEqualTo("FAILED");
        return new Scenario(bookingId, paymentId, token);
    }

    private record Scenario(String bookingId, String paymentId, String token) {
    }

    private String bookingStatus(String bookingId) {
        return bookingRepository.findById(UUID.fromString(bookingId)).orElseThrow().getStatus().name();
    }

    private long webhookEventCount(String eventId) {
        return webhookEventRepository.findAll().stream()
                .filter(event -> event.getEventId().equals(eventId))
                .count();
    }

    // --- the headline test -------------------------------------------------

    @Test
    @DisplayName("the same event delivered five times is processed exactly once")
    void repeatedDeliveryIsIdempotent() throws Exception {
        Scenario scenario = scenarioWithFailedPayment();
        String eventId = "evt-repeat-" + UUID.randomUUID();
        String body = webhookBody(eventId, scenario.paymentId(), scenario.bookingId(), "SUCCESS");

        List<MvcResult> results = new ArrayList<>();
        for (int attempt = 0; attempt < 5; attempt++) {
            results.add(sendWebhook(body));
        }

        assertThat(results).allSatisfy(result ->
                assertThat(result.getResponse().getStatus()).isEqualTo(200));

        assertThat(resultOf(results.get(0))).isEqualTo("PROCESSED");
        assertThat(results.subList(1, 5))
                .allSatisfy(result -> assertThat(resultOf(result)).isEqualTo("ALREADY_PROCESSED"));

        // Exactly one event row, and the state is what the first delivery left.
        assertThat(webhookEventCount(eventId)).isEqualTo(1);
        assertThat(paymentRepository.findById(UUID.fromString(scenario.paymentId()))
                .orElseThrow().getStatus().name()).isEqualTo("SUCCESS");
        assertThat(bookingStatus(scenario.bookingId())).isEqualTo("CONFIRMED");

        // And it is still that after the repeats: nothing moved.
        assertThat(bookingStatus(scenario.bookingId())).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("ten simultaneous deliveries of one event still process it once")
    void concurrentDeliveryIsIdempotent() throws Exception {
        Scenario scenario = scenarioWithFailedPayment();
        String eventId = "evt-parallel-" + UUID.randomUUID();
        String body = webhookBody(eventId, scenario.paymentId(), scenario.bookingId(), "SUCCESS");

        int parallel = 10;
        // Fired together, so they really contend for the same eventId rather than
        // being processed one after another.
        CountDownLatch startGun = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(parallel);

        try {
            List<Callable<MvcResult>> jobs = new ArrayList<>();
            for (int i = 0; i < parallel; i++) {
                jobs.add(() -> {
                    startGun.await(10, TimeUnit.SECONDS);
                    return sendWebhook(body);
                });
            }

            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Callable<MvcResult> job : jobs) {
                futures.add(pool.submit(job));
            }
            startGun.countDown();

            List<String> outcomes = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get(30, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                outcomes.add(resultOf(result));
            }

            assertThat(outcomes).filteredOn("PROCESSED"::equals).hasSize(1);
            assertThat(outcomes).filteredOn("ALREADY_PROCESSED"::equals)
                    .hasSize(parallel - 1);
        } finally {
            pool.shutdownNow();
        }

        // The database is the source of truth for "exactly once".
        assertThat(webhookEventCount(eventId)).isEqualTo(1);
        assertThat(paymentRepository.findById(UUID.fromString(scenario.paymentId()))
                .orElseThrow().getStatus().name()).isEqualTo("SUCCESS");
        assertThat(bookingStatus(scenario.bookingId())).isEqualTo("CONFIRMED");
    }

    // --- signature ---------------------------------------------------------

    @Test
    @DisplayName("an invalid signature is 401 and changes nothing")
    void invalidSignatureIsRejected() throws Exception {
        Scenario scenario = scenarioWithFailedPayment();
        String eventId = "evt-badsig-" + UUID.randomUUID();
        String body = webhookBody(eventId, scenario.paymentId(), scenario.bookingId(), "SUCCESS");

        mockMvc.perform(post("/payments/webhook/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", "0".repeat(64)) // right shape, wrong value
                        .content(body))
                .andExpect(status().isUnauthorized());

        assertThat(webhookEventCount(eventId)).isZero();
        assertThat(bookingStatus(scenario.bookingId())).isEqualTo("FAILED");
        assertThat(paymentRepository.findById(UUID.fromString(scenario.paymentId()))
                .orElseThrow().getStatus().name()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("a missing signature header is 401")
    void missingSignatureIsRejected() throws Exception {
        Scenario scenario = scenarioWithFailedPayment();
        String body = webhookBody("evt-nosig-" + UUID.randomUUID(),
                scenario.paymentId(), scenario.bookingId(), "SUCCESS");

        mockMvc.perform(post("/payments/webhook/")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        assertThat(bookingStatus(scenario.bookingId())).isEqualTo("FAILED");
    }

    // --- validation --------------------------------------------------------

    @Test
    @DisplayName("an unknown status value is 400 and records nothing")
    void unknownStatusIsRejected() throws Exception {
        Scenario scenario = scenarioWithFailedPayment();
        String eventId = "evt-badstatus-" + UUID.randomUUID();
        String body = webhookBody(eventId, scenario.paymentId(), scenario.bookingId(), "REFUNDED");

        mockMvc.perform(post("/payments/webhook/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", sign(body))
                        .content(body))
                .andExpect(status().isBadRequest());

        assertThat(webhookEventCount(eventId)).isZero();
    }

    @Test
    @DisplayName("malformed JSON with a valid signature is 400")
    void malformedBodyIsRejected() throws Exception {
        String body = "{ this is not json";
        mockMvc.perform(post("/payments/webhook/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", sign(body))
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // --- out-of-order and defensive cases ----------------------------------

    @Test
    @DisplayName("a late FAILED after a SUCCESS is ignored, not applied")
    void doesNotDowngradeASucceededPayment() throws Exception {
        Scenario scenario = scenarioWithFailedPayment();

        String successEvent = "evt-oo-success-" + UUID.randomUUID();
        MvcResult first = sendWebhook(webhookBody(
                successEvent, scenario.paymentId(), scenario.bookingId(), "SUCCESS"));
        assertThat(resultOf(first)).isEqualTo("PROCESSED");
        assertThat(bookingStatus(scenario.bookingId())).isEqualTo("CONFIRMED");

        // A different eventId, so this is a genuinely new event rather than a
        // duplicate: the provider is out of order, not repeating itself.
        String lateEvent = "evt-oo-late-" + UUID.randomUUID();
        MvcResult late = sendWebhook(webhookBody(
                lateEvent, scenario.paymentId(), scenario.bookingId(), "FAILED"));

        // 2xx so the provider stops retrying, but nothing changed.
        assertThat(late.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultOf(late)).isEqualTo("IGNORED_INVALID_TRANSITION");
        assertThat(bookingStatus(scenario.bookingId())).isEqualTo("CONFIRMED");
        assertThat(paymentRepository.findById(UUID.fromString(scenario.paymentId()))
                .orElseThrow().getStatus().name()).isEqualTo("SUCCESS");
        // Recorded for audit even though it was ignored.
        assertThat(webhookEventCount(lateEvent)).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown paymentId is 404 and is still recorded for audit")
    void unknownPaymentIsNotFound() throws Exception {
        Scenario scenario = scenarioWithFailedPayment();
        String eventId = "evt-unknownpay-" + UUID.randomUUID();
        String body = webhookBody(eventId, UUID.randomUUID().toString(), scenario.bookingId(), "SUCCESS");

        MvcResult result = sendWebhook(body);

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(resultOf(result)).isEqualTo("PAYMENT_NOT_FOUND");
        assertThat(webhookEventCount(eventId)).isEqualTo(1);
        // And crucially, no payment was invented from the webhook.
        assertThat(bookingStatus(scenario.bookingId())).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("a bookingId that does not match the payment is refused with 400")
    void mismatchedBookingIsRejected() throws Exception {
        Scenario scenario = scenarioWithFailedPayment();
        String otherBooking = createBooking(scenario.token());

        String eventId = "evt-mismatch-" + UUID.randomUUID();
        MvcResult result = sendWebhook(
                webhookBody(eventId, scenario.paymentId(), otherBooking, "SUCCESS"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(resultOf(result)).isEqualTo("BOOKING_MISMATCH");
        assertThat(bookingStatus(scenario.bookingId())).isEqualTo("FAILED");
        assertThat(bookingStatus(otherBooking)).isEqualTo("PENDING");
    }

    private String createBooking(String token) throws Exception {
        String bookingBody = """
                {"centreId":"11111111-1111-1111-1111-111111111111",\
                "testId":"a2222222-2222-2222-2222-222222222222",\
                "appointmentTime":"%s"}"""
                .formatted(Instant.now().plus(3, ChronoUnit.DAYS).toString());
        MvcResult booking = mockMvc.perform(post("/bookings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(bookingBody))
                .andExpect(status().isCreated())
                .andReturn();
        return JSON.readTree(booking.getResponse().getContentAsString()).get("id").asText();
    }
}