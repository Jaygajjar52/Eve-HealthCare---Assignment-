package com.eve.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests over the real HTTP stack: signup, login, booking and paying,
 * including the rules about whose bookings you may see.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthAndBookingIntegrationTest extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MockMvc mockMvc;

    // --- helpers -----------------------------------------------------------

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@eve.com";
    }

    private String signupAndGetToken(String email) throws Exception {
        String body = """
                {"name":"Test User","email":"%s","password":"secret123"}""".formatted(email);
        String response = mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("accessToken").asText();
    }

    private String login(String email, String password) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}""".formatted(email, password);
        String response = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", bearer(token));
    }

    private String createBooking(String token) throws Exception {
        String body = """
                {"centreId":"11111111-1111-1111-1111-111111111111",\
                "testId":"a1111111-1111-1111-1111-111111111111",\
                "appointmentTime":"%s"}"""
                .formatted(Instant.now().plus(3, ChronoUnit.DAYS).toString());
        MvcResult result = mockMvc.perform(asUser(post("/bookings"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String bookAndPay(String token) throws Exception {
        String bookingId = createBooking(token);
        String body = """
                {"bookingId":"%s","simulateStatus":"SUCCESS"}""".formatted(bookingId);
        MvcResult result = mockMvc.perform(asUser(post("/payments"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString()).get("bookingStatus").asText();
    }

    // --- happy path --------------------------------------------------------

    @Test
    @DisplayName("signup, login, book and pay leaves the booking CONFIRMED")
    void happyPath() throws Exception {
        String email = uniqueEmail("happy");
        String token = signupAndGetToken(email);
        assertThat(token).isNotBlank();

        assertThat(bookAndPay(token)).isEqualTo("CONFIRMED");

        mockMvc.perform(asUser(get("/bookings"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.content[0].amount").value(350.00))
                .andExpect(jsonPath("$.content[0].testName").value("Complete Blood Count (CBC)"));
    }

    @Test
    @DisplayName("login works for a user created by signup")
    void loginAfterSignup() throws Exception {
        String email = uniqueEmail("login");
        signupAndGetToken(email);

        assertThat(login(email, "secret123")).isNotBlank();
    }

    @Test
    @DisplayName("the seeded admin can log in")
    void seededAdminCanLogIn() throws Exception {
        assertThat(login("admin@evehealth.com", "Admin@123")).isNotBlank();
    }

    // --- auth rules --------------------------------------------------------

    @Test
    @DisplayName("a duplicate email is rejected with 409")
    void duplicateSignupIsConflict() throws Exception {
        String email = uniqueEmail("dupe");
        signupAndGetToken(email);

        String body = """
                {"name":"Again","email":"%s","password":"secret123"}""".formatted(email);
        mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("An account with that email already exists."));
    }

    @Test
    @DisplayName("a wrong password is 401 and an unknown email is 401 with the same message")
    void wrongCredentialsAreUnauthorized() throws Exception {
        String email = uniqueEmail("wrongpw");
        signupAndGetToken(email);

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"not-the-password"}""".formatted(email)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ghost@nowhere.com","password":"secret123"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password."));
    }

    @Test
    @DisplayName("validation failures come back as 400 with fieldErrors")
    void signupValidationReturnsFieldErrors() throws Exception {
        mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"X","email":"not-an-email","password":"short"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]").exists());
    }

    @Test
    @DisplayName("an endpoint that needs a token answers 401 without one")
    void protectedEndpointNeedsAToken() throws Exception {
        mockMvc.perform(get("/bookings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Bearer")));
    }

    @Test
    @DisplayName("a garbage or tampered token is 401")
    void invalidTokensAreRejected() throws Exception {
        mockMvc.perform(get("/bookings").header("Authorization", "Bearer nonsense"))
                .andExpect(status().isUnauthorized());

        String email = uniqueEmail("tamper");
        signupAndGetToken(email);
        String token = login(email, "secret123");

        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        mockMvc.perform(get("/bookings").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a non-admin cannot create a centre")
    void nonAdminCannotCreateCentre() throws Exception {
        String token = signupAndGetToken(uniqueEmail("notadmin"));

        mockMvc.perform(asUser(post("/centres"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sneaky Centre","city":"Pune"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    // --- ownership ---------------------------------------------------------

    @Test
    @DisplayName("user A cannot read or cancel user B's booking")
    void bookingsAreScopedToTheirOwner() throws Exception {
        String aliceToken = signupAndGetToken(uniqueEmail("alice"));
        String bobToken = signupAndGetToken(uniqueEmail("bob"));
        String aliceBooking = createBooking(aliceToken);

        mockMvc.perform(asUser(get("/bookings/" + aliceBooking), bobToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Booking not found")));

        mockMvc.perform(asUser(patch("/bookings/" + aliceBooking + "/cancel"), bobToken))
                .andExpect(status().isNotFound());

        // Still Alice's, and still PENDING, so Bob changed nothing.
        mockMvc.perform(asUser(get("/bookings/" + aliceBooking), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("one user's list never contains another user's booking")
    void listIsScopedToTheCaller() throws Exception {
        String aliceToken = signupAndGetToken(uniqueEmail("lalice"));
        String bobToken = signupAndGetToken(uniqueEmail("lbob"));
        String aliceBooking = createBooking(aliceToken);

        MvcResult bobList = mockMvc.perform(asUser(get("/bookings?size=100"), bobToken))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(bobList.getResponse().getContentAsString()).doesNotContain(aliceBooking);
    }

    // --- booking rules -----------------------------------------------------

    @Test
    @DisplayName("a malformed booking id is 400 but a well-formed unknown id is 404")
    void distinguishesBadIdFromUnknownId() throws Exception {
        String token = signupAndGetToken(uniqueEmail("ids"));

        mockMvc.perform(asUser(get("/bookings/not-a-uuid"), token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(asUser(get("/bookings/" + UUID.randomUUID()), token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("an appointment time in the past is rejected with 400")
    void pastAppointmentTimeIsRejected() throws Exception {
        String token = signupAndGetToken(uniqueEmail("past"));
        String body = """
                {"centreId":"11111111-1111-1111-1111-111111111111",\
                "testId":"a1111111-1111-1111-1111-111111111111",\
                "appointmentTime":"%s"}"""
                .formatted(Instant.now().minus(3, ChronoUnit.DAYS).toString());

        mockMvc.perform(asUser(post("/bookings"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'appointmentTime')]").exists());
    }

    @Test
    @DisplayName("booking a test the centre does not offer is 404")
    void unavailableCentreTestPairIsNotFound() throws Exception {
        String token = signupAndGetToken(uniqueEmail("notoffered"));
        // Andheri does not offer the MRI.
        String body = """
                {"centreId":"11111111-1111-1111-1111-111111111111",\
                "testId":"a4444444-4444-4444-4444-111111111111",\
                "appointmentTime":"%s"}"""
                .formatted(Instant.now().plus(3, ChronoUnit.DAYS).toString());

        mockMvc.perform(asUser(post("/bookings"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("does not offer")));
    }

    @Test
    @DisplayName("a client cannot dictate the amount, it comes from the catalogue")
    void amountComesFromTheCatalogue() throws Exception {
        String token = signupAndGetToken(uniqueEmail("amount"));
        String body = """
                {"centreId":"11111111-1111-1111-1111-111111111111",\
                "testId":"a1111111-1111-1111-1111-111111111111",\
                "appointmentTime":"%s","amount":1.00}"""
                .formatted(Instant.now().plus(3, ChronoUnit.DAYS).toString());

        MvcResult result = mockMvc.perform(asUser(post("/bookings"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(350.00))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"amount\":1.00");
    }

    @Test
    @DisplayName("cancelling twice is 409 the second time")
    void doubleCancelIsConflict() throws Exception {
        String token = signupAndGetToken(uniqueEmail("cancel"));
        String bookingId = createBooking(token);

        mockMvc.perform(asUser(patch("/bookings/" + bookingId + "/cancel"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(asUser(patch("/bookings/" + bookingId + "/cancel"), token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("CANCELLED")));
    }

    @Test
    @DisplayName("a booking can only be paid for by its owner")
    void cannotPaySomeoneElsesBooking() throws Exception {
        String aliceToken = signupAndGetToken(uniqueEmail("payalice"));
        String bobToken = signupAndGetToken(uniqueEmail("paybob"));
        String aliceBooking = createBooking(aliceToken);

        mockMvc.perform(asUser(post("/payments"), bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingId":"%s","simulateStatus":"SUCCESS"}""".formatted(aliceBooking)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Booking not found")));
    }

    @Test
    @DisplayName("paying an already confirmed booking is 409")
    void payingTwiceIsConflict() throws Exception {
        String token = signupAndGetToken(uniqueEmail("twice"));
        String bookingId = createBooking(token);
        String body = """
                {"bookingId":"%s","simulateStatus":"SUCCESS"}""".formatted(bookingId);

        mockMvc.perform(asUser(post("/payments"), token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mockMvc.perform(asUser(post("/payments"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already been paid")));
    }

    // --- catalogue reads ---------------------------------------------------

    @Test
    @DisplayName("the catalogue is browsable without a token")
    void catalogueIsPublic() throws Exception {
        mockMvc.perform(get("/centres"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        mockMvc.perform(get("/centres?city=bengaluru"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/centres/11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tests").isArray());

        mockMvc.perform(get("/centres/99999999-9999-9999-9999-999999999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("errors never leak a stack trace or SQL")
    void errorsDoNotLeakInternals() throws Exception {
        MvcResult result = mockMvc.perform(get("/centres/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Exception", "at org.springframework", "at java.", "select ", "org.hibernate");
        assertThat(body).startsWith("{");
        JsonNode error = JSON.readTree(body);
        assertThat(error.has("timestamp")).isTrue();
        assertThat(error.has("status")).isTrue();
        assertThat(error.has("error")).isTrue();
        assertThat(error.has("message")).isTrue();
        assertThat(error.has("path")).isTrue();
    }
}