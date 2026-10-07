package br.com.vistoria.worker.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Validates provider mode and payment provider configuration at startup.
 *
 * <p>Ensures that configured integration modes (Pleno, Payment) correspond to valid bean names,
 * failing fast with a clear message rather than waiting for a request to fail or silently
 * falling back to mock. (A assinatura/Autentique agora vive no {@code inspecta-messenger}.)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProviderModeValidator implements ApplicationRunner {

    @Value("${inspecta.pleno.mode:mock}")
    private String plenoMode;

    @Value("${inspecta.payment.provider:mock}")
    private String paymentProvider;

    private final ApplicationContext context;

    private static final Map<String, Set<String>> VALID_MODES = Map.of(
            "pleno", Set.of("http", "mock"),
            "payment", Set.of("mock", "abacatepay")
    );

    /**
     * The actual Spring property suffix for each provider. Pleno exposes {@code inspecta.pleno.mode};
     * payment exposes {@code inspecta.payment.provider} ({@code application.yml}). Error messages must
     * name the real property so an operator fixing a bad env var isn't sent looking for a key that
     * doesn't exist.
     */
    private static final Map<String, String> PROPERTY_KEYS = Map.of(
            "pleno", "inspecta.pleno.mode",
            "payment", "inspecta.payment.provider"
    );

    @Override
    public void run(ApplicationArguments args) throws Exception {
        validateMode("pleno", plenoMode);
        validateMode("payment", paymentProvider);
        log.info("Provider mode validation passed: pleno={}, payment={}", plenoMode, paymentProvider);
    }

    private void validateMode(String provider, String mode) {
        Set<String> validModes = VALID_MODES.get(provider);
        if (validModes == null || !validModes.contains(mode)) {
            // Ordena para a mensagem de erro ser deterministica — Set.of(...) nao
            // tem ordem de iteracao estavel entre JVMs.
            String validList = (validModes != null ? validModes : Set.<String>of())
                    .stream().sorted().collect(java.util.stream.Collectors.joining(", "));
            String propertyKey = PROPERTY_KEYS.getOrDefault(provider, "inspecta." + provider + ".mode");
            throw new IllegalStateException(
                    "Invalid " + propertyKey + ": '" + mode + "'. " +
                    "Valid values are: " + validList);
        }
    }
}
