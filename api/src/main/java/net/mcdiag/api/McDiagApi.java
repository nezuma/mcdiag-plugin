package net.mcdiag.api;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Публичный API агента MCDiag. Регистрируется в {@link org.bukkit.plugin.ServicesManager}:
 * подключите {@code mcdiag-api.jar} как compileOnly и добавьте {@code softdepend: [MCDiag]}.
 *
 * <pre>{@code
 * McDiagApi api = McDiagApi.get();
 * if (api != null) {
 *     api.report(this, Severity.HIGH, "payments.gateway", "Платёжный шлюз не отвечает 30 секунд");
 * }
 * }</pre>
 *
 * <p>Методы потокобезопасны, кроме {@link #registerModule}: его вызывают из главного потока.
 */
public interface McDiagApi {

    /** Растёт при несовместимых изменениях. */
    int API_VERSION = 1;

    /** API агента или null, если MCDiag не установлен или ещё не включён. */
    static McDiagApi get() {
        try {
            RegisteredServiceProvider<McDiagApi> provider = Bukkit.getServicesManager().getRegistration(McDiagApi.class);
            return provider == null ? null : provider.getProvider();
        } catch (NoClassDefFoundError e) {
            return null;
        }
    }

    String agentVersion();

    /** Модуль включится, как только позволят настройки и панель. Модули снимаются при выключении {@code owner}. */
    void registerModule(Plugin owner, AgentModule module);

    void unregisterModules(Plugin owner);

    List<ModuleInfo> modules();

    /**
     * Сообщить о проблеме: она появится в журнале ошибок панели рядом с ошибками из лога.
     * Повторы одного сообщения схлопываются в счётчик.
     *
     * @param category короткий ключ {@code [a-z0-9_.-]{1,48}}, например {@code database.timeout}
     * @param message  описание для владельца сервера, до 512 символов
     */
    void report(Plugin source, Severity severity, String category, String message);
}
