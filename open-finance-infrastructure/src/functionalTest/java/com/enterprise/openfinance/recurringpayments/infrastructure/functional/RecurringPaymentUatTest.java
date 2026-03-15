package com.enterprise.openfinance.recurringpayments.infrastructure.functional;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.ComponentScan;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@Tag("functional")
@Tag("e2e")
@Tag("uat")
@SpringBootTest(
        classes = RecurringPaymentUatTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
)
class RecurringPaymentUatTest {

    @LocalServerPort
    private int port;

    @Test
    void shouldCompleteVrpJourney() {
        String consentId = createConsent("5000.00");

        Response payment = submitPayment(consentId, "IDEMP-UAT-001", "100.00")
                .statusCode(201)
                .body("Data.Status", equalTo("Accepted"))
                .extract()
                .response();

        String paymentId = payment.path("Data.PaymentId");

        baseRequest()
                .when()
                .get("/open-finance/v1/vrp/payments/{paymentId}", paymentId)
                .then()
                .statusCode(200)
                .body("Data.PaymentId", equalTo(paymentId))
                .body("Data.Status", equalTo("Accepted"));
    }

    @Test
    void shouldEnforceCumulativeLimitAndRevocation() {
        String consentId = createConsent("5000.00");

        for (int i = 1; i <= 4; i++) {
            submitPayment(consentId, "IDEMP-UAT-CUM-" + i, "1001.00")
                    .statusCode(201);
        }

        submitPayment(consentId, "IDEMP-UAT-CUM-5", "1001.00")
                .statusCode(400)
                .body("code", equalTo("BUSINESS_RULE_VIOLATION"))
                .body("message", containsString("Limit Exceeded"));

        baseRequest()
                .queryParam("reason", "User request")
                .when()
                .delete("/open-finance/v1/vrp/payment-consents/{consentId}", consentId)
                .then()
                .statusCode(204);

        submitPayment(consentId, "IDEMP-UAT-REV", "10.00")
                .statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }

    @Test
    void shouldAllowOnlyOneConcurrentPaymentWhenLimitWouldBeBreached() throws Exception {
        String consentId = createConsent("5000.00");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Integer> taskA = concurrentPaymentTask(consentId, "IDEMP-UAT-RACE-A", ready, start);
        Callable<Integer> taskB = concurrentPaymentTask(consentId, "IDEMP-UAT-RACE-B", ready, start);

        Future<Integer> f1 = executor.submit(taskA);
        Future<Integer> f2 = executor.submit(taskB);

        org.junit.jupiter.api.Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();

        int status1 = f1.get(10, TimeUnit.SECONDS);
        int status2 = f2.get(10, TimeUnit.SECONDS);

        executor.shutdownNow();

        org.assertj.core.api.Assertions.assertThat(List.of(status1, status2))
                .containsExactlyInAnyOrder(201, 400);
    }

    private Callable<Integer> concurrentPaymentTask(String consentId,
                                                    String idempotencyKey,
                                                    CountDownLatch ready,
                                                    CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return submitPayment(consentId, idempotencyKey, "3000.00")
                    .extract()
                    .response()
                    .statusCode();
        };
    }

    private String createConsent(String amount) {
        Response response = baseRequest()
                .contentType("application/json")
                .body(consentPayload("PSU-001", amount, "AED"))
                .when()
                .post("/open-finance/v1/vrp/payment-consents")
                .then()
                .statusCode(201)
                .body("Data.Status", equalTo("Authorised"))
                .extract()
                .response();

        return response.path("Data.ConsentId");
    }

    private io.restassured.response.ValidatableResponse submitPayment(String consentId,
                                                                      String idempotencyKey,
                                                                      String amount) {
        return baseRequest()
                .header("x-idempotency-key", idempotencyKey)
                .contentType("application/json")
                .body(paymentPayload(consentId, amount, "AED"))
                .when()
                .post("/open-finance/v1/vrp/payments")
                .then();
    }

    private RequestSpecification baseRequest() {
        return given()
                .baseUri("http://localhost")
                .port(port)
                .accept("application/json")
                .header("Authorization", "DPoP functional-token")
                .header("DPoP", "functional-proof")
                .header("X-FAPI-Interaction-ID", "ix-recurring-payments-functional-" + Instant.now().toEpochMilli())
                .header("x-fapi-financial-id", "TPP-001");
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
