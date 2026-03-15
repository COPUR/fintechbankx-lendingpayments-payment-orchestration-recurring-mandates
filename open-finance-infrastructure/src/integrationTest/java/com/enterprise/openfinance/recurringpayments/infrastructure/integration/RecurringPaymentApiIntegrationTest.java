package com.enterprise.openfinance.recurringpayments.infrastructure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@SpringBootTest(
        classes = RecurringPaymentApiIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
)
@AutoConfigureMockMvc(addFilters = false)
class RecurringPaymentApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldCreateConsentAndReturnCacheHitOnSecondRead() throws Exception {
        MvcResult created = mockMvc.perform(withBaseHeaders(post("/open-finance/v1/vrp/payment-consents"))
                        .contentType("application/json")
                        .content(consentPayload("PSU-001", "5000.00", "AED")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.Data.Status").value("Authorised"))
                .andReturn();

        String consentId = consentId(created.getResponse().getContentAsString());

        mockMvc.perform(withBaseHeaders(get("/open-finance/v1/vrp/payment-consents/{consentId}", consentId)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-OF-Cache", "MISS"));

        mockMvc.perform(withBaseHeaders(get("/open-finance/v1/vrp/payment-consents/{consentId}", consentId)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-OF-Cache", "HIT"));
    }

    @Test
    void shouldSubmitPaymentAndSupportIdempotencyAndEtag() throws Exception {
        String consentId = createConsent();

        MvcResult firstPayment = mockMvc.perform(withPaymentHeaders(post("/open-finance/v1/vrp/payments"), "IDEMP-001")
                        .contentType("application/json")
                        .content(paymentPayload(consentId, "100.00", "AED")))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-OF-Idempotency", "MISS"))
                .andExpect(jsonPath("$.Data.Status").value("Accepted"))
                .andReturn();

        mockMvc.perform(withPaymentHeaders(post("/open-finance/v1/vrp/payments"), "IDEMP-001")
                        .contentType("application/json")
                        .content(paymentPayload(consentId, "100.00", "AED")))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-OF-Idempotency", "HIT"))
                .andExpect(jsonPath("$.Meta.IdempotencyReplay").value(true));

        String paymentId = paymentId(firstPayment.getResponse().getContentAsString());

        MvcResult read = mockMvc.perform(withBaseHeaders(get("/open-finance/v1/vrp/payments/{paymentId}", paymentId)))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.Data.PaymentId").value(paymentId))
                .andReturn();

        String etag = read.getResponse().getHeader("ETag");

        mockMvc.perform(withBaseHeaders(get("/open-finance/v1/vrp/payments/{paymentId}", paymentId))
                        .header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void shouldRejectLimitExceededCumulativeAndRevokedConsent() throws Exception {
        String consentId = createConsent();

        mockMvc.perform(withPaymentHeaders(post("/open-finance/v1/vrp/payments"), "IDEMP-LIMIT")
                        .contentType("application/json")
                        .content(paymentPayload(consentId, "5001.00", "AED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.message", Matchers.containsString("Limit Exceeded")));

        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(withPaymentHeaders(post("/open-finance/v1/vrp/payments"), "IDEMP-CUM-" + i)
                            .contentType("application/json")
                            .content(paymentPayload(consentId, "1001.00", "AED")))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(withPaymentHeaders(post("/open-finance/v1/vrp/payments"), "IDEMP-CUM-5")
                        .contentType("application/json")
                        .content(paymentPayload(consentId, "1001.00", "AED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        mockMvc.perform(withBaseHeaders(delete("/open-finance/v1/vrp/payment-consents/{consentId}", consentId))
                        .param("reason", "User request"))
                .andExpect(status().isNoContent());

        mockMvc.perform(withPaymentHeaders(post("/open-finance/v1/vrp/payments"), "IDEMP-REVOKED")
                        .contentType("application/json")
                        .content(paymentPayload(consentId, "10.00", "AED")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private String createConsent() throws Exception {
        MvcResult result = mockMvc.perform(withBaseHeaders(post("/open-finance/v1/vrp/payment-consents"))
                        .contentType("application/json")
                        .content(consentPayload("PSU-001", "5000.00", "AED")))
                .andExpect(status().isCreated())
                .andReturn();
        return consentId(result.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder withPaymentHeaders(MockHttpServletRequestBuilder builder, String idemKey) {
        return withBaseHeaders(builder)
                .header("x-idempotency-key", idemKey);
    }

    private MockHttpServletRequestBuilder withBaseHeaders(MockHttpServletRequestBuilder builder) {
        return builder
                .header("Authorization", "DPoP integration-token")
                .header("DPoP", "proof-jwt")
                .header("X-FAPI-Interaction-ID", "ix-recurring-payments-integration")
                .header("x-fapi-financial-id", "TPP-001")
                .accept("application/json");
    }

    private String consentId(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        return root.path("Data").path("ConsentId").asText();
    }

    private String paymentId(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        return root.path("Data").path("PaymentId").asText();
    }

    private static String consentPayload(String psuId, String maxAmount, String currency) {
        return """
                {
                  "Data": {
                    "PsuId": "%s",
                    "Limit": {
                      "Amount": "%s",
                      "Currency": "%s"
                    },
                    "ExpiryDateTime": "2099-01-01T00:00:00Z"
                  }
                }
                """.formatted(psuId, maxAmount, currency);
    }

    private static String paymentPayload(String consentId, String amount, String currency) {
        return """
                {
                  "Data": {
                    "ConsentId": "%s",
                    "InstructedAmount": {
                      "Amount": "%s",
                      "Currency": "%s"
                    }
                  }
                }
                """.formatted(consentId, amount, currency);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            SecurityAutoConfiguration.class,
            OAuth2ResourceServerAutoConfiguration.class,
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            MongoAutoConfiguration.class,
            MongoDataAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class
    })
    @ComponentScan(basePackages = {
            "com.enterprise.openfinance.recurringpayments.application",
            "com.enterprise.openfinance.recurringpayments.infrastructure"
    })
    static class TestApplication {
    }
}
