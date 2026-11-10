package net.mcdiag.agent.privileges;

import java.util.ArrayList;
import java.util.List;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.actionlog.Action;
import net.luckperms.api.event.EventBus;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.log.LogPublishEvent;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.model.data.DataType;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.plugin.Plugin;

/**
 * Подписка на события LuckPerms. Единственный класс агента, который ссылается на API LuckPerms:
 * без этого плагина он не загружается вовсе, модуль прав работает без него.
 */
final class LuckPermsBridge {

    private final List<EventSubscription<?>> subscriptions = new ArrayList<>();

    private LuckPermsBridge() {
    }

    static LuckPermsBridge hook(Plugin agent, PrivilegeAudit audit) {
        LuckPerms lp = LuckPermsProvider.get();
        EventBus bus = lp.getEventBus();
        LuckPermsBridge bridge = new LuckPermsBridge();
        // События LuckPerms приходят в его потоках; PrivilegeAudit потокобезопасен.
        bridge.subscriptions.add(bus.subscribe(agent, LogPublishEvent.class, e -> {
            Action entry = e.getEntry();
            Action.Target target = entry.getTarget();
            audit.luckPermsLog(entry.getTimestamp().toEpochMilli(), entry.getSource().getName(),
                    target.getName(), target.getType().name().toLowerCase(java.util.Locale.ROOT), entry.getDescription());
        }));
        bridge.subscriptions.add(bus.subscribe(agent, NodeAddEvent.class, e -> {
            String type = e.getTarget() instanceof User ? "user" : e.getTarget() instanceof Group ? "group" : "other";
            audit.luckPermsNode(System.currentTimeMillis(), e.getTarget().getFriendlyName(), type,
                    e.getNode().getKey(), e.getNode().getValue(), e.getDataType() == DataType.TRANSIENT);
        }));
        return bridge;
    }

    void close() {
        for (EventSubscription<?> s : subscriptions) {
            s.close();
        }
        subscriptions.clear();
    }
}
