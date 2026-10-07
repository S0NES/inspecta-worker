package br.com.vistoria.worker.signature;

import br.com.vistoria.core.modules.assinatura.AssinaturaService;
import br.com.vistoria.core.modules.assinatura.Signatario;
import br.com.vistoria.core.modules.notify.NotifyTemplates;
import br.com.vistoria.core.shared.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SignatureLembreteCron")
class SignatureLembreteCronTest {

    @Mock private AssinaturaService assinaturaService;
    @Mock private OutboxService outbox;

    private SignatureLembreteCron cron;

    @BeforeEach
    void setUp() {
        cron = new SignatureLembreteCron(assinaturaService, outbox);
        ReflectionTestUtils.setField(cron, "enabled", true);
        ReflectionTestUtils.setField(cron, "janelaDias", 7);
        ReflectionTestUtils.setField(cron, "intervaloHoras", 72);
    }

    private Signatario pendente(String nome, String url) {
        Signatario s = new Signatario();
        s.setId(UUID.randomUUID());
        s.setNome(nome);
        s.setEmail(nome.toLowerCase() + "@x.co");
        s.setSigningUrl(url);
        return s;
    }

    @Test
    @DisplayName("publica assinatura-lembrete e carimba lembrete_enviado_em por pendente")
    void lembrar_publicaEcarimba() {
        Signatario a = pendente("Maria", "https://assina.ae/m");
        Signatario b = pendente("Joao", "https://assina.ae/j");
        when(assinaturaService.pendentesParaLembrete(any(), any())).thenReturn(List.of(a, b));

        cron.lembrar();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(outbox, org.mockito.Mockito.times(2)).publish(key.capture(), body.capture());
        assertThat(key.getAllValues()).containsOnly("notify.requested.assinatura-lembrete");
        assertThat(body.getAllValues().get(0)).containsEntry("templateId", NotifyTemplates.ASSINATURA_LEMBRETE);
        @SuppressWarnings("unchecked")
        Map<String, Object> vars = (Map<String, Object>) body.getAllValues().get(0).get("variaveis");
        assertThat(vars).containsEntry("signingUrl", "https://assina.ae/m");
        verify(assinaturaService).registrarLembreteEnviado(a.getId());
        verify(assinaturaService).registrarLembreteEnviado(b.getId());
    }

    @Test
    @DisplayName("passa janela e intervalo configurados")
    void lembrar_passaConfig() {
        when(assinaturaService.pendentesParaLembrete(any(), any())).thenReturn(List.of());
        cron.lembrar();
        verify(assinaturaService).pendentesParaLembrete(Duration.ofDays(7), Duration.ofHours(72));
    }

    @Test
    @DisplayName("enabled=false -> no-op")
    void lembrar_desabilitado() {
        ReflectionTestUtils.setField(cron, "enabled", false);
        cron.lembrar();
        verify(assinaturaService, never()).pendentesParaLembrete(any(), any());
        verify(outbox, never()).publish(anyString(), any());
    }
}
