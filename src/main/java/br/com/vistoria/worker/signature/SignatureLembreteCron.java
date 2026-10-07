package br.com.vistoria.worker.signature;

import br.com.vistoria.core.modules.assinatura.AssinaturaService;
import br.com.vistoria.core.modules.assinatura.Signatario;
import br.com.vistoria.core.modules.notify.NotifyTemplates;
import br.com.vistoria.core.shared.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Lembrete de assinatura pendente. Toda manhã varre {@code signatarios} ainda {@code pendente}
 * de um documento já enviado à Autentique, dentro da janela do link (expira em 7d), e reenvia
 * por e-mail <b>o mesmo {@code signing_url}</b> (template utilitário {@code assinatura_lembrete}).
 *
 * <p>Status vem só do webhook: {@code signature.accepted} marca {@code assinado} e a linha sai
 * da varredura. Não há consulta de status na Autentique aqui.</p>
 *
 * <p>Tudo configurável por env:
 * {@code SIGNATURE_REMINDER_ENABLED} / {@code _CRON} / {@code _WINDOW_DAYS} / {@code _SPACING_HOURS}.
 * {@code intervalo-horas} = espaçamento mínimo entre lembretes pro mesmo signatário
 * (72h numa janela de 7d ≈ lembrete no dia ~3 e ~6; 24h = diário; &gt; janela = uma vez).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SignatureLembreteCron {

    private final AssinaturaService assinaturaService;
    private final OutboxService outbox;

    @Value("${inspecta.signature.reminder.enabled:true}") private boolean enabled;
    @Value("${inspecta.signature.reminder.janela-dias:7}") private int janelaDias;
    @Value("${inspecta.signature.reminder.intervalo-horas:72}") private int intervaloHoras;

    @Scheduled(cron = "${inspecta.signature.reminder.cron:0 0 8 * * *}")
    @SchedulerLock(name = "signatureLembrete", lockAtMostFor = "15m")
    @Transactional
    public void lembrar() {
        if (!enabled) return;

        List<Signatario> pendentes = assinaturaService.pendentesParaLembrete(
                Duration.ofDays(janelaDias), Duration.ofHours(intervaloHoras));
        if (pendentes.isEmpty()) return;

        for (Signatario s : pendentes) {
            outbox.publish("notify.requested.assinatura-lembrete", Map.of(
                    "eventId", "sign-lembrete-" + s.getId() + "-" + (System.currentTimeMillis() / 3_600_000L),
                    "canal", "email",
                    "tipoComunicacao", "assinatura",
                    "templateId", NotifyTemplates.ASSINATURA_LEMBRETE,
                    "destinatario", s.getEmail(),
                    "variaveis", Map.of("nome", s.getNome(), "signingUrl", s.getSigningUrl())));
            assinaturaService.registrarLembreteEnviado(s.getId());
        }
        log.info("[signature-lembrete] {} lembrete(s) de assinatura enfileirado(s)", pendentes.size());
    }
}
