package br.com.vistoria.worker.signature;

import br.com.vistoria.core.events.EventTypes;
import br.com.vistoria.core.modules.assinatura.AssinaturaService;
import br.com.vistoria.core.modules.assinatura.Signatario;
import br.com.vistoria.core.modules.laudo.Laudo;
import br.com.vistoria.core.modules.laudo.LaudoService;
import br.com.vistoria.core.shared.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SignatureConsumer (preparo)")
class SignatureConsumerTest {

    @Mock private AssinaturaService assinaturaService;
    @Mock private LaudoService laudoService;
    @Mock private OutboxService outbox;

    private SignatureConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SignatureConsumer(assinaturaService, laudoService, outbox);
    }

    private Message msg(String payload, String routingKey) {
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey(routingKey);
        return new Message(payload.getBytes(), props);
    }

    @Test
    @DisplayName("send: publica jobs.signature.create-document com pdfArquivoId + signers")
    void send_publicaCreateDocument() {
        UUID laudoId = UUID.randomUUID();
        UUID pdfId = UUID.randomUUID();
        Laudo laudo = new Laudo();
        laudo.setPdfArquivoId(pdfId);
        when(laudoService.get(laudoId)).thenReturn(laudo);

        Signatario s1 = new Signatario(); s1.setId(UUID.randomUUID()); s1.setNome("Maria");
        Signatario s2 = new Signatario(); s2.setId(UUID.randomUUID()); s2.setNome("Joao");
        when(assinaturaService.signatariosDoDocumento("laudo", laudoId)).thenReturn(List.of(s1, s2));

        consumer.on(msg("{\"documentoTipo\":\"laudo\",\"documentoId\":\"" + laudoId + "\"}", EventTypes.SIGNATURE_REQUESTED));

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(outbox).publish(eq(EventTypes.SIGNATURE_CREATE_DOCUMENT), body.capture());
        assertThat(body.getValue()).containsEntry("pdfArquivoId", pdfId).containsEntry("documentoId", laudoId);
        assertThat((List<?>) body.getValue().get("signers")).hasSize(2);
    }

    @Test
    @DisplayName("send: laudo sem PDF -> relança (retry)")
    void send_semPdf_relanca() {
        UUID laudoId = UUID.randomUUID();
        when(laudoService.get(laudoId)).thenReturn(new Laudo());  // pdfArquivoId null

        assertThatThrownBy(() -> consumer.on(
                msg("{\"documentoTipo\":\"laudo\",\"documentoId\":\"" + laudoId + "\"}", EventTypes.SIGNATURE_REQUESTED)))
                .isInstanceOf(IllegalStateException.class);
        verify(outbox, never()).publish(anyString(), any());
    }

    @Test
    @DisplayName("send: sem signatarios -> no-op")
    void send_semSignatarios() {
        UUID laudoId = UUID.randomUUID();
        Laudo laudo = new Laudo();
        laudo.setPdfArquivoId(UUID.randomUUID());
        when(laudoService.get(laudoId)).thenReturn(laudo);
        when(assinaturaService.signatariosDoDocumento("laudo", laudoId)).thenReturn(List.of());

        consumer.on(msg("{\"documentoTipo\":\"laudo\",\"documentoId\":\"" + laudoId + "\"}", EventTypes.SIGNATURE_REQUESTED));

        verify(outbox, never()).publish(anyString(), any());
    }

    @Test
    @DisplayName("routing key inesperada -> no-op")
    void routingInesperada() {
        consumer.on(msg("{}", "jobs.signature.webhook-received"));
        verify(laudoService, never()).get(any());
    }
}
