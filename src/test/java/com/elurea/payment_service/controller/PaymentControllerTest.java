package com.elurea.payment_service.controller;

import com.elurea.payment_service.config.AppProperties;
import com.elurea.payment_service.security.SecurityConfig;
import com.elurea.payment_service.security.SecurityProblemHandler;
import com.elurea.payment_service.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {PaymentController.class, InternalPaymentController.class},
        properties = "app.services.internal-api-key=test-internal-key")
@Import({SecurityConfig.class, SecurityProblemHandler.class, PaymentControllerTest.Config.class})
class PaymentControllerTest {

    @TestConfiguration
    @EnableConfigurationProperties(AppProperties.class)
    static class Config {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    private static RequestPostProcessor customer() {
        return jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Test
    void paymentsRequireAToken() throws Exception {
        mockMvc.perform(get("/api/payments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void createRequiresAnOrderId() throws Exception {
        mockMvc.perform(post("/api/payments").with(customer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.orderId").exists());
        verifyNoInteractions(paymentService);
    }

    @Test
    void customersCannotIssueRefunds() throws Exception {
        mockMvc.perform(post("/api/payments/{id}/refund", UUID.randomUUID()).with(customer()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(paymentService);
    }

    @Test
    void internalRefundRejectsCustomerTokens() throws Exception {
        mockMvc.perform(post("/internal/payments/orders/{id}/refund", UUID.randomUUID()).with(customer()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(paymentService);
    }

    @Test
    void internalRefundRejectsWrongKey() throws Exception {
        mockMvc.perform(post("/internal/payments/orders/{id}/refund", UUID.randomUUID())
                        .header("X-Internal-Api-Key", "wrong"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(paymentService);
    }

    @Test
    void internalRefundAcceptsServiceKey() throws Exception {
        UUID orderId = UUID.randomUUID();

        mockMvc.perform(post("/internal/payments/orders/{id}/refund", orderId)
                        .header("X-Internal-Api-Key", "test-internal-key"))
                .andExpect(status().isNoContent());
        verify(paymentService).refundOrder(orderId);
    }

    @Test
    void sandboxResultValidatesOutcome() throws Exception {
        mockMvc.perform(post("/api/payments/{id}/sandbox-result", UUID.randomUUID()).with(customer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\": \"MAYBE\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(paymentService);
    }

    @Test
    void unknownSortFieldIsRejected() throws Exception {
        mockMvc.perform(get("/api/payments").with(customer()).param("sort", "clientSecret"))
                .andExpect(status().isBadRequest());
        verify(paymentService, org.mockito.Mockito.never()).getAll(any(), any(), any());
    }
}
