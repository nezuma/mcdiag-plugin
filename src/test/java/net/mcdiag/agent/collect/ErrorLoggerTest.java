package net.mcdiag.agent.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ErrorLoggerTest {

    @Test
    void takesPluginNameFromCoreMessages() {
        assertEquals("WorldGuard", ErrorCaptureHandler.loggerOf("Minecraft",
                "Could not pass event PlayerMoveEvent to WorldGuard v7.0.9+5934e49"));
        assertEquals("My Plugin", ErrorCaptureHandler.loggerOf("",
                "Error occurred while enabling My Plugin v1.2 (Is it up to date?)"));
        assertEquals("Essentials", ErrorCaptureHandler.loggerOf(null,
                "Plugin Essentials v2.20.1 generated an exception while executing task 42"));
        // Логгер самого плагина не трогаем, непонятный текст — тоже.
        assertEquals("LuckPerms", ErrorCaptureHandler.loggerOf("LuckPerms", "Could not pass event X to Other v1"));
        assertEquals("Minecraft", ErrorCaptureHandler.loggerOf("Minecraft", "Exception ticking world"));
    }
}
