package br.com.vistoria.worker.payment;

import br.com.vistoria.core.modules.financeiro.Pagamento;
import br.com.vistoria.core.modules.financeiro.PagamentoService;
import br.com.vistoria.worker.payment.gateway.PaymentGateway;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rede de segurança do PIX transparente: confere no provedor os PIX ainda pendentes e aplica
 * "pago" quando o webhook não chegou (ou chegou num formato que não casou). Também faz o fluxo
 * funcionar em dev, onde o provedor não alcança o webhook. Idempotente — {@code aplicarEvento}
 * ignora quem já estava pago.
 */
@Component
@Slf4j
public class PixReconciliacaoCron {

    private final PagamentoService pagamentoService;
    private final ApplicationContext context;

    @Value("${inspecta.payment.provider:mock}")
    private String providerAtivo;

    @Autowired
    public PixReconciliacaoCron(PagamentoService pagamentoService, ApplicationContext context) {
        this.pagamentoService = pagamentoService;
        this.context = context;
    }

    @Scheduled(fixedDelayString = "${inspecta.payment.pix-reconciliacao-ms:20000}")
    @SchedulerLock(name = "pixReconciliacao", lockAtMostFor = "2m")
    public void reconciliar() {
        PaymentGateway gateway = context.getBean(providerAtivo, PaymentGateway.class);
        for (Pagamento p : pagamentoService.pixPendentes()) {
            try {
                if ("pago".equals(gateway.pixStatus(p.getPixId()))) {
                    log.info("[pix] pagamento {} confirmado por reconciliação pixId={}", p.getId(), p.getPixId());
                    pagamentoService.aplicarEvento("pix-check-" + p.getPixId(), p.getPixId(), Pagamento.Status.pago.name());
                }
            } catch (RuntimeException ex) {
                log.warn("[pix] reconciliação falhou pagamento={} pixId={}: {}", p.getId(), p.getPixId(), ex.getMessage());
            }
        }
    }
}
