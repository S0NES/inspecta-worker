package br.com.vistoria.worker.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DlqMonitor}.
 *
 * Covers:
 * - DLQ depth monitoring for all job queues
 * - Gauge metrics reporting
 * - Error handling when queues are unavailable
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DlqMonitor DLQ depth metrics (T7.7)")
class DlqMonitorTest {

    @Mock private RabbitAdmin rabbitAdmin;
    @Mock private MeterRegistry meterRegistry;

    private DlqMonitor dlqMonitor;

    @BeforeEach
    void setUp() throws Exception {
        dlqMonitor = new DlqMonitor();
        setFieldViaReflection(dlqMonitor, "rabbitAdmin", rabbitAdmin);
        setFieldViaReflection(dlqMonitor, "meterRegistry", meterRegistry);
    }

    private void setFieldViaReflection(Object obj, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = DlqMonitor.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    @Test
    @DisplayName("monitorDlqDepths runs without errors when all queues are empty")
    void monitorDlqDepths_allQueuesEmpty_runsSuccessfully() throws Exception {
        when(rabbitAdmin.getQueueProperties(anyString()))
            .thenReturn(null);

        assertThatCode(() -> dlqMonitor.monitorDlqDepths())
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("monitorDlqDepths continues when RabbitAdmin throws exception")
    void monitorDlqDepths_rabbitAdminException_continuesMonitoring() throws Exception {
        when(rabbitAdmin.getQueueProperties(anyString()))
            .thenThrow(new RuntimeException("Connection refused"));

        assertThatCode(() -> dlqMonitor.monitorDlqDepths())
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("monitorDlqDepths is scheduled via @Scheduled annotation")
    void monitorDlqDepths_hasScheduledAnnotation() throws Exception {
        boolean hasScheduled = java.util.Arrays.stream(
            DlqMonitor.class.getDeclaredMethod("monitorDlqDepths").getDeclaredAnnotations()
        ).anyMatch(a -> a.annotationType().getSimpleName().equals("Scheduled"));

        assertThat(hasScheduled)
            .as("monitorDlqDepths() should be annotated with @Scheduled")
            .isTrue();
    }

    @Test
    @DisplayName("monitorDlqDepths registers the dlq.depth gauge tagged with queue name and current message count")
    void monitorDlqDepths_queueWithMessages_registersGaugeWithCount() throws Exception {
        Properties props = new Properties();
        props.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 7);
        // Every other DLQ in the fixed monitored list resolves to "no queue" (null);
        // only q.assignment.dlq carries messages, so the gauge call for it can be isolated.
        when(rabbitAdmin.getQueueProperties(anyString())).thenReturn(null);
        when(rabbitAdmin.getQueueProperties("q.assignment.dlq")).thenReturn(props);

        dlqMonitor.monitorDlqDepths();

        ArgumentCaptor<Number> valueCaptor = ArgumentCaptor.forClass(Number.class);
        verify(meterRegistry).gauge(eq("dlq.depth"), eq(Tags.of("queue", "assignment")), valueCaptor.capture());
        assertThat(valueCaptor.getValue().intValue()).isEqualTo(7);
    }

    @Test
    @DisplayName("monitorDlqDepths checks every configured job queue's DLQ, not just the first")
    void monitorDlqDepths_checksAllQueues() throws Exception {
        when(rabbitAdmin.getQueueProperties(anyString())).thenReturn(null);

        dlqMonitor.monitorDlqDepths();

        for (String dlq : new String[]{"q.assignment.dlq", "q.pdf.dlq", "q.image.dlq",
                "q.pleno.dlq", "q.signature.dlq", "q.payment.dlq", "q.metrics.dlq"}) {
            org.mockito.Mockito.verify(rabbitAdmin).getQueueProperties(dlq);
        }
    }

    @Test
    @DisplayName("DlqMonitor is marked as EnableScheduling component")
    void dlqMonitor_isComponent() throws Exception {
        boolean isComponent = java.util.Arrays.stream(
            DlqMonitor.class.getDeclaredAnnotations()
        ).anyMatch(a -> a.annotationType().getSimpleName().equals("Component"));

        assertThat(isComponent)
            .as("DlqMonitor should be marked as @Component")
            .isTrue();
    }

}
