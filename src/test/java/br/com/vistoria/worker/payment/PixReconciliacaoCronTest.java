package br.com.vistoria.worker.payment;

import br.com.vistoria.core.modules.financeiro.Pagamento;
import br.com.vistoria.core.modules.financeiro.PagamentoService;
import br.com.vistoria.worker.payment.gateway.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PixReconciliacaoCron")
class PixReconciliacaoCronTest {

    @Mock private PagamentoService pagamentoService;
    @Mock private ApplicationContext context;
    @Mock private PaymentGateway gateway;

    private PixReconciliacaoCron cron;

    @BeforeEach
    void setUp() {
        cron = new PixReconciliacaoCron(pagamentoService, context);
        ReflectionTestUtils.setField(cron, "providerAtivo", "mock");
        when(context.getBean("mock", PaymentGateway.class)).thenReturn(gateway);
    }

    private Pagamento pix(String pixId) {
        Pagamento p = new Pagamento();
        p.setId(UUID.randomUUID());
        p.setPixId(pixId);
        return p;
    }

    @Test
    @DisplayName("PIX pago no provedor -> aplicarEvento(pago) com o pixId")
    void aplicaPagoQuandoProvedorConfirma() {
        when(pagamentoService.pixPendentes()).thenReturn(List.of(pix("pix_1")));
        when(gateway.pixStatus("pix_1")).thenReturn("pago");

        cron.reconciliar();

        verify(pagamentoService).aplicarEvento("pix-check-pix_1", "pix_1", "pago");
    }

    @Test
    @DisplayName("PIX ainda pendente: nada é aplicado")
    void ignoraPendente() {
        when(pagamentoService.pixPendentes()).thenReturn(List.of(pix("pix_2")));
        when(gateway.pixStatus("pix_2")).thenReturn("pendente");

        cron.reconciliar();

        verify(pagamentoService, never()).aplicarEvento(any(), any(), any());
    }

    @Test
    @DisplayName("falha em um PIX não interrompe os demais")
    void falhaIsolada() {
        when(pagamentoService.pixPendentes()).thenReturn(List.of(pix("pix_a"), pix("pix_b")));
        when(gateway.pixStatus("pix_a")).thenThrow(new IllegalStateException("provider fora"));
        when(gateway.pixStatus("pix_b")).thenReturn("pago");

        cron.reconciliar();

        verify(pagamentoService).aplicarEvento("pix-check-pix_b", "pix_b", "pago");
    }
}
