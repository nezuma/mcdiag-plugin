package net.mcdiag.agent.module;

import net.mcdiag.api.Severity;

/** Куда уходят проблемы из {@link net.mcdiag.api.McDiagApi#report}: журнал ошибок модуля logs. */
@FunctionalInterface
public interface IssueSink {

    void report(String plugin, Severity severity, String category, String message);
}
