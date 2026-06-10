package com.hdu.apisensitivities.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ValidationUtilsTest {

    @Test
    void testCalculateEntropy() {
        double e1 = ValidationUtils.calculateEntropy("111111");
        double e2 = ValidationUtils.calculateEntropy("abcXYZ123");
        assertTrue(e1 >= 0.0);
        assertTrue(e2 > e1);
    }

    @Test
    void testValidatePhoneNumber() {
        assertTrue(ValidationUtils.validatePhoneNumber("13800138000"));
        assertFalse(ValidationUtils.validatePhoneNumber("12345"));
    }

    @Test
    void testValidateBankCard() {
        // 4111 1111 1111 1111 is a commonly used test Visa card number (Luhn valid)
        assertTrue(ValidationUtils.validateBankCard("4111 1111 1111 1111"));
        assertFalse(ValidationUtils.validateBankCard("1111 1111 1111 1111"));
    }

    @Test
    void testValidateEmail() {
        assertTrue(ValidationUtils.validateEmail("test@example.com"));
        assertFalse(ValidationUtils.validateEmail("not-an-email"));
    }
}
