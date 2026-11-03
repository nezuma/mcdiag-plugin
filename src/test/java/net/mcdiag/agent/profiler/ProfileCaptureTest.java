package net.mcdiag.agent.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ProfileCaptureTest {

    @Test
    void hotFrameSkipsJdk() {
        StackTraceElement[] stack = {
            new StackTraceElement("java.util.HashMap", "get", null, -1),
            new StackTraceElement("com.gmail.nossr50.util.Misc", "isNear", null, -1),
            new StackTraceElement("net.minecraft.server.MinecraftServer", "tick", null, -1),
        };
        assertEquals("Misc.isNear", ProfileCapture.hotFrame(stack));
        assertNull(ProfileCapture.hotFrame(new StackTraceElement[] {stack[0]}));
    }
}
