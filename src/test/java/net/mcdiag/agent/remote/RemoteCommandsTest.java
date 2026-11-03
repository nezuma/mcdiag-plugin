package net.mcdiag.agent.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RemoteCommandsTest {

    @Test
    void parsesCommandsFromIngestResponse() {
        String body = "{\"success\":true,\"data\":{\"duplicate\":false,\"events\":0,\"commands\":["
                + "{\"id\":\"0f8fad5b-d9cb-469f-a165-70867728950e\",\"type\":\"ping\"},"
                + "{\"id\":\"7c9e6679-7425-40de-944b-e07fc1f90ae7\",\"type\":\"profile\",\"seconds\":60},"
                + "{\"id\":\"not-a-uuid\",\"type\":\"ping\"}]}}";
        List<RemoteCommand> commands = RemoteCommandParser.parse(body);
        assertEquals(2, commands.size());
        assertEquals(new RemoteCommand("0f8fad5b-d9cb-469f-a165-70867728950e", "ping", 0), commands.get(0));
        assertEquals(60, commands.get(1).seconds());
        assertTrue(RemoteCommandParser.parse("{\"success\":true,\"data\":{}}").isEmpty());
        assertTrue(RemoteCommandParser.parse(null).isEmpty());
    }

    @Test
    void capsCommandsPerResponse() {
        StringBuilder body = new StringBuilder("{\"commands\":[");
        for (int i = 0; i < 9; i++) {
            body.append(i > 0 ? "," : "").append("{\"id\":\"0f8fad5b-d9cb-469f-a165-7086772895").append(10 + i).append("\",\"type\":\"ping\"}");
        }
        assertEquals(RemoteCommandParser.MAX_COMMANDS, RemoteCommandParser.parse(body.append("]}").toString()).size());
    }

    @Test
    void masksSecretsInJvmArgs() {
        assertEquals("-Ddb.password=***", RemoteCommands.maskArg("-Ddb.password=hunter2"));
        assertEquals("-Dfile.encoding=***", RemoteCommands.maskArg("-Dfile.encoding=UTF-8"));
        assertEquals("-Xmx8G", RemoteCommands.maskArg("-Xmx8G"));
        assertEquals("***", RemoteCommands.maskArg("--api-token=abc"));
    }
}
