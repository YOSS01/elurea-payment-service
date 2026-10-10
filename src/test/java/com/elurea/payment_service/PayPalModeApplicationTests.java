package com.elurea.payment_service;

import com.elurea.payment_service.controller.PayPalWebhookController;
import com.elurea.payment_service.gateway.PayPalPaymentGateway;
import com.elurea.payment_service.gateway.PaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole application must start with PayPal selected, with the webhook endpoint active.
 */
@SpringBootTest(properties = {
        "app.payments.provider=paypal",
        "app.payments.paypal.client-id=dummy-client",
        "app.payments.paypal.client-secret=dummy-secret",
        "app.payments.paypal.webhook-id=WH-dummy",
        "app.payments.paypal.exchange-rate=0.092"
})
class PayPalModeApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void startsWithPayPalGatewayAndWebhookEndpoint() {
        assertThat(context.getBean(PaymentGateway.class)).isInstanceOf(PayPalPaymentGateway.class);
        assertThat(context.getBeansOfType(PayPalWebhookController.class)).hasSize(1);
    }
}
