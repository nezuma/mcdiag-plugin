package net.mcdiag.agent.profiler;

import java.util.random.RandomGenerator;

/**
 * Через сколько снимать следующий сэмпл.
 *
 * <ul>
 *   <li>Обычно редко: для минутной статистики этого достаточно, вес сэмпла равен прошедшему времени.
 *   <li>Если тик тянется дольше одного интервала, чаще: так у лаг-спайка остаётся подробная картина.
 *   <li>Снимок стека дорожает (много потоков, долгий выход на safepoint) — интервал растёт сам:
 *       снимки занимают не больше 0,5% времени, а внутри долгого тика не больше 2%. Долгие тики
 *       редки, и без подробных сэмплов у спайка не определить виновника.
 *   <li>Случайный разброс обязателен: тик длится ровно 50 мс, и сэмплы с тем же периодом
 *       попадали бы в одну и ту же фазу тика и видели бы только её.
 * </ul>
 */
final class SamplePacer {

    /** Обычно снимки стека занимают не больше 1/200 (0,5%) времени */
    static final long NORMAL_BUDGET_DIVISOR = 200;
    /** Внутри долгого тика — не больше 1/50 (2%) */
    static final long LONG_TICK_BUDGET_DIVISOR = 50;
    /** Даже при дорогих снимках спайк должен получить хотя бы редкие сэмплы */
    private static final long MAX_DELAY_MS = 500;
    private static final int COST_SMOOTHING = 8;

    private final long baseMs;
    private final long denseMs;
    private final RandomGenerator random;
    private long costNanos;
    private long nominalMs;

    SamplePacer(long baseMs, long denseMs, RandomGenerator random) {
        this.baseMs = baseMs;
        this.denseMs = denseMs;
        this.random = random;
        this.nominalMs = baseMs;
    }

    /**
     * @param longTick  тот же тик, что и в прошлом сэмпле: он идёт дольше интервала
     * @param readNanos сколько занял снимок стека; 0, если стек не снимали
     */
    long next(boolean longTick, long readNanos) {
        if (readNanos > 0) {
            costNanos = costNanos == 0 ? readNanos : costNanos + (readNanos - costNanos) / COST_SMOOTHING;
        }
        long divisor = longTick ? LONG_TICK_BUDGET_DIVISOR : NORMAL_BUDGET_DIVISOR;
        long budgetMs = Math.min(MAX_DELAY_MS, costNanos * divisor / 1_000_000);
        nominalMs = Math.max(longTick ? denseMs : baseMs, budgetMs);
        // Равномерно в [0,5; 1,5) от номинала: среднее не меняется, совпадения с тиком нет.
        return Math.max(1, nominalMs / 2 + random.nextLong(Math.max(1, nominalMs)));
    }

    /** Больше этого один сэмпл не весит: пауза JVM не должна «съесть» всю минуту. */
    double maxWeightMs() {
        return nominalMs * 5.0;
    }

    long nominalMs() {
        return nominalMs;
    }
}
