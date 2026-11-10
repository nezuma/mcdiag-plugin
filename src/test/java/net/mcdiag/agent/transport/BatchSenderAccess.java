package net.mcdiag.agent.transport;

import java.util.List;
import java.util.UUID;
import net.mcdiag.agent.buffer.TelemetryBuffer;
import net.mcdiag.agent.module.Channel;

/** Доступ к сборке батча для тестов из других пакетов. */
public final class BatchSenderAccess {

    private BatchSenderAccess() {
    }

    public static int assemble(StringBuilder sb, TelemetryBuffer buffer, UUID batchId, List<Channel> channels) {
        return BatchSender.assemble(sb, buffer, 100, batchId, 1, "{}", channels);
    }
}
