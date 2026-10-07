package br.com.vistoria.worker.shared;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link Eventos} JSON parsing helper.
 *
 * Covers:
 * - Single-encoded JSON payload parsing (post-T3.2 fix)
 * - Correct field extraction
 * - Invalid/malformed JSON handling
 */
@DisplayName("Eventos JSON parser")
class EventosTest {

    @Test
    @DisplayName("parse correctly decodes single-encoded JSON payload")
    void parse_singleEncodedPayload_returnsJsonNode() {
        byte[] payload = """
            {
              "eventId": "123e4567-e89b-12d3-a456-426614174000",
              "type": "jobs.signature.send",
              "occurredAt": "2026-08-04T10:30:00Z",
              "tenantId": 42,
              "payload": {
                "documentoTipo": "laudo",
                "documentoId": 99
              }
            }
            """.getBytes();

        JsonNode node = Eventos.parse(payload);

        assertThat(node.path("eventId").asText())
            .isEqualTo("123e4567-e89b-12d3-a456-426614174000");
        assertThat(node.path("type").asText())
            .isEqualTo("jobs.signature.send");
        assertThat(node.path("tenantId").asInt())
            .isEqualTo(42);
        assertThat(node.path("payload").path("documentoId").asLong())
            .isEqualTo(99L);
    }

    @Test
    @DisplayName("parse extracts nested payload fields correctly")
    void parse_nestedPayload_extractsFields() {
        byte[] payload = """
            {
              "eventId": "evt-001",
              "type": "jobs.payment.webhook-received",
              "payload": {
                "eventId": "stripe-evt-123",
                "provider": "abacatepay",
                "payloadBruto": "{\\"status\\":\\"paid\\"}"
              }
            }
            """.getBytes();

        JsonNode node = Eventos.parse(payload);
        JsonNode innerPayload = node.path("payload");

        assertThat(innerPayload.path("eventId").asText())
            .isEqualTo("stripe-evt-123");
        assertThat(innerPayload.path("provider").asText())
            .isEqualTo("abacatepay");
    }

    @Test
    @DisplayName("parse handles missing optional fields gracefully")
    void parse_optionalFields_returnsEmptyForMissing() {
        byte[] payload = """
            {
              "eventId": "evt-002",
              "type": "jobs.assignment.requested",
              "payload": {
                "servicoId": 123
              }
            }
            """.getBytes();

        JsonNode node = Eventos.parse(payload);

        assertThat(node.path("tenantId").asInt(-1))
            .isEqualTo(-1);
        assertThat(node.path("payload").path("motivo").asText(""))
            .isEmpty();
    }

    @Test
    @DisplayName("parse throws on invalid JSON")
    void parse_invalidJson_throws() {
        byte[] payload = "not valid json {".getBytes();

        assertThatThrownBy(() -> Eventos.parse(payload))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Evento invalido")
            .hasCauseInstanceOf(com.fasterxml.jackson.core.JsonParseException.class);
    }

    @Test
    @DisplayName("parse throws on null input")
    void parse_nullInput_throws() {
        assertThatThrownBy(() -> Eventos.parse(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("parse handles empty JSON object")
    void parse_emptyObject_returnsEmptyNode() {
        byte[] payload = "{}".getBytes();

        JsonNode node = Eventos.parse(payload);

        assertThat(node.path("eventId").asText(""))
            .isEmpty();
        assertThat(node.isObject())
            .isTrue();
    }

    @Test
    @DisplayName("parse preserves numeric precision for large IDs")
    void parse_largeNumericIds_preservesPrecision() {
        byte[] payload = """
            {
              "eventId": "evt",
              "payload": {
                "servicoId": 9223372036854775806,
                "pequeno": 42
              }
            }
            """.getBytes();

        JsonNode node = Eventos.parse(payload);

        assertThat(node.path("payload").path("servicoId").asLong())
            .isEqualTo(9223372036854775806L);
        assertThat(node.path("payload").path("pequeno").asInt())
            .isEqualTo(42);
    }
}
