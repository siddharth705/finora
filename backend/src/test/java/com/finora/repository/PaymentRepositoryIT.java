package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Payment;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentRepositoryIT extends AbstractIntegrationTest {

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private UserRepository userRepository;

    private User createUser() {
        User user = new User();
        user.setEmail("payment-tax-invoice-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Payment Tax Invoice IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    @Test
    void savesAndReloadsTheOptionalTaxAndInvoiceFields() {
        User user = createUser();
        Payment payment = new Payment();
        payment.setUserId(user.getId());
        payment.setAmount(new BigDecimal("943.82"));
        payment.setCurrency("INR");
        payment.setProvider("RAZORPAY");
        payment.setStatus(Payment.STATUS_SUCCESS);
        payment.setBaseAmount(new BigDecimal("799.00"));
        payment.setTaxAmount(new BigDecimal("144.82"));
        payment.setInvoiceId("inv_test_123");
        payment.setInvoiceUrl("https://razorpay.com/invoices/inv_test_123");
        Payment saved = paymentRepository.save(payment);

        Payment reloaded = paymentRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getBaseAmount()).isEqualByComparingTo(new BigDecimal("799.00"));
        assertThat(reloaded.getTaxAmount()).isEqualByComparingTo(new BigDecimal("144.82"));
        assertThat(reloaded.getInvoiceId()).isEqualTo("inv_test_123");
        assertThat(reloaded.getInvoiceUrl()).isEqualTo("https://razorpay.com/invoices/inv_test_123");
    }

    @Test
    void leavesTheNewFieldsNullWhenNeverSet() {
        // Every existing writer (RazorpayWebhookDispatcher.handleCharged/handlePending, Plan 1) does
        // not set these -- confirms that keeps working exactly as it does today, with no NOT NULL
        // constraint newly required of them.
        User user = createUser();
        Payment payment = new Payment();
        payment.setUserId(user.getId());
        payment.setAmount(new BigDecimal("399.00"));
        payment.setCurrency("INR");
        payment.setProvider("RAZORPAY");
        payment.setStatus(Payment.STATUS_SUCCESS);
        Payment saved = paymentRepository.save(payment);

        Payment reloaded = paymentRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getBaseAmount()).isNull();
        assertThat(reloaded.getTaxAmount()).isNull();
        assertThat(reloaded.getInvoiceId()).isNull();
        assertThat(reloaded.getInvoiceUrl()).isNull();
    }
}
