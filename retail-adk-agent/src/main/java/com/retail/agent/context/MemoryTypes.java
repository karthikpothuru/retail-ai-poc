package com.retail.agent.context;

/** Closed classifications used by the repository, write policy and context manifest. */
public final class MemoryTypes {
    private MemoryTypes() {}
    public enum Type { WORKING, EPISODIC, SEMANTIC, PROCEDURAL }
    public enum Verification { CANDIDATE, VERIFIED, APPROVED }
    public enum Sensitivity { PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED }
    public enum Lifecycle { ACTIVE, SUPERSEDED, REVOKED }
    public enum Capability { WRITE_MEMORY, VERIFY_SEMANTIC, APPROVE_PROCEDURE }
    public enum Origin { USER_INPUT, CONVERSATION, RETRIEVED_EVIDENCE, TOOL_RESULT, KNOWLEDGE, PROCEDURE, SYSTEM, POLICY }
}
