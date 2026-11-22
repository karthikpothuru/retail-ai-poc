package com.retail.agent.context;

import java.sql.Connection;
import java.util.UUID;

/** Synchronous JDBC mutation context; always cleared, and deliberately not inherited across threads. */
final class GovernanceAuditScope implements AutoCloseable {
    private static final ThreadLocal<GovernanceAuditScope> CURRENT=new ThreadLocal<>();
    final TrustedCallerBoundary.Caller caller;
    final String operation,reason,repositoryAction;
    boolean recorded;
    GovernanceAuditScope(TrustedCallerBoundary.Caller caller,String operation,String reason,String repositoryAction){
        if(CURRENT.get()!=null)throw new IllegalStateException("Nested governed mutation not supported");
        this.caller=caller;this.operation=operation;this.reason=reason;this.repositoryAction=repositoryAction;CURRENT.set(this);
    }
    static void success(Connection connection,ContextIdentity.Access access,String action,UUID memoryId,Long version)throws Exception {
        var scope=CURRENT.get();
        if(scope==null||!scope.repositoryAction.equals(action))return;
        if(!scope.caller.access().equals(access))throw new SecurityException("Governance transaction scope mismatch");
        PostgresGovernanceAudit.insert(connection,GovernanceAudit.event(scope.caller,true,scope.operation,"SUCCESS",scope.reason,memoryId,version));
        scope.recorded=true;
    }
    @Override public void close(){CURRENT.remove();}
}
