package br.com.vistoria.worker.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Monitors Dead Letter Queue (DLQ) depths for inspecta-worker job queues.
 *
 * <h3>DLQ Drain/Replay Procedure</h3>
 * <p>When messages accumulate in a DLQ (detected by non-zero gauge), follow these steps:</p>
 * <ol>
 *   <li>Identify which DLQ has accumulated messages (check {@code dlq.depth} metric tagged with queue name)</li>
 *   <li>SSH into a pod: {@code kubectl exec -it inspecta-worker-<pod> -- bash}</li>
 *   <li>Query the DLQ: {@code rabbitmqctl list_queues name messages | grep q.<type>.dlq}</li>
 *   <li>If messages require investigation, export them first:
 *     {@code rabbitmq-queues drain_queue q.<type>.dlq <output-file>}</li>
 *   <li>Root cause the failure (check pod logs, database state, external API status)</li>
 *   <li>Fix the root cause (code deploy, data correction, or wait for external service recovery)</li>
 *   <li>Replay messages by publishing them back to the main queue or deleting if unrecoverable</li>
 * </ol>
 * <p>Example replay (after fix): {@code rabbitmq-queues requeue-all q.<type>.dlq q.<type>}</p>
 *
 * <h3>Alerting</h3>
 * <p>Configure your monitoring system (Prometheus, Datadog, etc.) to alert when any
 * {@code dlq.depth} metric > 0 for more than 5 minutes. DLQ messages indicate either
 * a bug that needs immediate attention or external dependency failure.</p>
 */
@Component
@EnableScheduling
@ConditionalOnClass(RabbitAdmin.class)
public class DlqMonitor {

    private static final Logger log = LoggerFactory.getLogger(DlqMonitor.class);
    private static final List<String> DLQ_NAMES = List.of(
            "q.assignment.dlq",
            "q.pdf.dlq",
            "q.image.dlq",
            "q.pleno.dlq",
            "q.signature.dlq",
            "q.payment.dlq",
            "q.metrics.dlq"
    );

    @Autowired
    private AmqpAdmin rabbitAdmin;

    @Autowired
    private MeterRegistry meterRegistry;

    @Scheduled(fixedDelay = 60000)  // Check DLQ depths every 60 seconds
    public void monitorDlqDepths() {
        for (String dlqName : DLQ_NAMES) {
            try {
                var queueInfo = rabbitAdmin.getQueueProperties(dlqName);
                if (queueInfo != null) {
                    int messageCount = (int) queueInfo.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
                    String queueType = extractQueueType(dlqName);
                    meterRegistry.gauge("dlq.depth", Tags.of("queue", queueType), messageCount);

                    if (messageCount > 0) {
                        log.warn("DLQ {} has {} messages", dlqName, messageCount);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to query DLQ {}: {}", dlqName, e.getMessage());
            }
        }
    }

    private String extractQueueType(String dlqName) {
        // Extract queue type from "q.xxx.dlq" -> "xxx"
        return dlqName.substring(2, dlqName.length() - 4);
    }
}
