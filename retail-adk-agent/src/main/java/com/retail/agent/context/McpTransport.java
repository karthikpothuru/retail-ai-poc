package com.retail.agent.context;

import java.time.Duration;
import java.util.*;

/** Small application transport seam; SDK implementation is separate from deterministic server fakes. */
public interface McpTransport {
    record DiscoveredTool(String name,Map<String,Object> inputSchema){}
    record Reply(boolean error,List<String> text){public Reply{text=List.copyOf(text);}}
    class ConnectionFailure extends RuntimeException {public ConnectionFailure(){super("MCP connection failed");}ConnectionFailure(Class<?> kind){super("MCP transport failure: "+kind.getSimpleName());}}
    class AuthorizationFailure extends SecurityException {
        public AuthorizationFailure(){super("MCP downstream authorization denied");}
    }
    class TimeoutFailure extends RuntimeException {
        public TimeoutFailure(){super("MCP transport timed out");}
    }
    class InvalidResponse extends RuntimeException {
        public InvalidResponse(){super("MCP response used unsupported content");}
    }
    List<DiscoveredTool> discover(McpDelegation.Credential credential,Duration timeout);
    Reply call(McpDelegation.Credential credential,String tool,Map<String,Object> arguments,UUID requestId,Duration timeout);
}
