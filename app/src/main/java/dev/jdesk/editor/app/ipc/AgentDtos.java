package dev.jdesk.editor.app.ipc;

import java.util.List;

/** Wire DTOs for embedded agent session control. Public records of restricted types only. */
public final class AgentDtos {

    private AgentDtos() {}

    public record StartRequest(String prompt) {}

    public record AgentSession(String sessionId, String provider) {}

    public record StatusRequest(String sessionId) {}

    public record AgentStatus(String sessionId, boolean done, boolean success,
            List<String> toolCalls, String result) {}
}
