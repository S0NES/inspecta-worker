package br.com.vistoria.worker.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ProviderModeValidator}. Cobre Pleno + Payment (a assinatura/Autentique
 * migrou pro inspecta-messenger).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProviderModeValidator startup validation (T7.15)")
class ProviderModeValidatorTest {

    @Mock private ApplicationContext applicationContext;

    private ApplicationArguments appArgs;

    @BeforeEach
    void setUp() {
        appArgs = mock(ApplicationArguments.class);
    }

    @Nested
    @DisplayName("valid provider modes")
    class ValidModesTests {

        @Test
        @DisplayName("accepts valid Pleno modes: http and mock")
        void run_validPlenoModes_passes() throws Exception {
            validateMode("pleno", "http", true);
            validateMode("pleno", "mock", true);
        }

        @Test
        @DisplayName("accepts valid Payment providers: mock and abacatepay")
        void run_validPaymentProviders_passes() throws Exception {
            validateMode("payment", "mock", true);
            validateMode("payment", "abacatepay", true);
        }

        @Test
        @DisplayName("combination of all valid modes passes")
        void run_validCombination_passes() throws Exception {
            ProviderModeValidator validator = new ProviderModeValidator(mock(ApplicationContext.class));
            setField(validator, "plenoMode", "http");
            setField(validator, "paymentProvider", "abacatepay");
            assertThatCode(() -> validator.run(appArgs)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("invalid provider modes")
    class InvalidModesTests {

        @Test
        @DisplayName("rejects invalid Pleno mode")
        void run_invalidPlenoMode_throws() throws Exception {
            ProviderModeValidator validator = new ProviderModeValidator(mock(ApplicationContext.class));
            setField(validator, "plenoMode", "invalid");
            setField(validator, "paymentProvider", "mock");
            assertThatThrownBy(() -> validator.run(appArgs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid inspecta.pleno.mode: 'invalid'")
                .hasMessageContaining("Valid values are: http, mock");
        }

        @Test
        @DisplayName("rejects invalid Payment provider")
        void run_invalidPaymentProvider_throws() throws Exception {
            ProviderModeValidator validator = new ProviderModeValidator(mock(ApplicationContext.class));
            setField(validator, "plenoMode", "mock");
            setField(validator, "paymentProvider", "stripe");
            assertThatThrownBy(() -> validator.run(appArgs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid inspecta.payment.provider: 'stripe'")
                .hasMessageContaining("Valid values are: abacatepay, mock");
        }

        @Test
        @DisplayName("rejects case-sensitive mode values")
        void run_caseInsensitive_throws() throws Exception {
            ProviderModeValidator validator = new ProviderModeValidator(mock(ApplicationContext.class));
            setField(validator, "plenoMode", "Http");
            setField(validator, "paymentProvider", "mock");
            assertThatThrownBy(() -> validator.run(appArgs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid inspecta.pleno.mode: 'Http'");
        }

        @Test
        @DisplayName("rejects empty mode string")
        void run_emptyMode_throws() throws Exception {
            ProviderModeValidator validator = new ProviderModeValidator(mock(ApplicationContext.class));
            setField(validator, "plenoMode", "");
            setField(validator, "paymentProvider", "mock");
            assertThatThrownBy(() -> validator.run(appArgs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid inspecta.pleno.mode: ''");
        }
    }

    @Nested
    @DisplayName("error messages")
    class ErrorMessageTests {

        @Test
        @DisplayName("error message lists all valid values")
        void run_invalidMode_errorListsValidValues() throws Exception {
            ProviderModeValidator validator = new ProviderModeValidator(mock(ApplicationContext.class));
            setField(validator, "plenoMode", "invalid");
            setField(validator, "paymentProvider", "mock");
            assertThatThrownBy(() -> validator.run(appArgs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Valid values are:")
                .hasMessageContaining("http")
                .hasMessageContaining("mock");
        }
    }

    @Nested
    @DisplayName("validation timing")
    class ValidationTimingTests {

        @Test
        @DisplayName("validation runs at application startup via ApplicationRunner")
        void validator_implementsApplicationRunner() {
            ProviderModeValidator validator = new ProviderModeValidator(mock(ApplicationContext.class));
            assertThat(validator instanceof org.springframework.boot.ApplicationRunner).isTrue();
        }

        @Test
        @DisplayName("fails fast on invalid configuration before app fully starts")
        void validator_failsFast() throws Exception {
            ProviderModeValidator validator = new ProviderModeValidator(mock(ApplicationContext.class));
            setField(validator, "plenoMode", "invalid-mode");
            setField(validator, "paymentProvider", "mock");
            assertThatThrownBy(() -> validator.run(appArgs)).isInstanceOf(IllegalStateException.class);
        }
    }


    private void validateMode(String provider, String mode, boolean shouldPass) throws Exception {
        ProviderModeValidator validator = new ProviderModeValidator(mock(ApplicationContext.class));
        setField(validator, "plenoMode", "mock");
        setField(validator, "paymentProvider", "mock");
        switch (provider) {
            case "pleno" -> setField(validator, "plenoMode", mode);
            case "payment" -> setField(validator, "paymentProvider", mode);
        }
        if (shouldPass) {
            assertThatCode(() -> validator.run(appArgs)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> validator.run(appArgs)).isInstanceOf(IllegalStateException.class);
        }
    }

    private void setField(Object obj, String fieldName, String value) throws Exception {
        java.lang.reflect.Field field = ProviderModeValidator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
