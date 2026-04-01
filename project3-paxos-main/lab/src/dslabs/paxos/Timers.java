package dslabs.paxos;

import dslabs.framework.Command;
import dslabs.framework.Timer;
import lombok.Data;

/* -------------------------------------------------------------------------
 * Client Timers
 * -----------------------------------------------------------------------*/
@Data
final class ClientTimer implements Timer {
    static final int CLIENT_RETRY_MILLIS = 100;
    
    // Tracks the specific command being retried so the client doesn't 
    // accidentally trigger retries for old/resolved commands.
    private final Command command; 
}

/* -------------------------------------------------------------------------
 * Server Timers
 * -----------------------------------------------------------------------*/

@Data
final class ElectionTimer implements Timer {
    // Empty class. The timer framework automatically passes this object 
    // back to onElectionTimer() when the set time expires. 
    // The timeout length is determined dynamically in the PaxosServer 
    // (usually randomized between 150ms and 300ms).
}

@Data
final class HeartbeatTimer implements Timer {
    // Heartbeats need to be significantly faster than the election timeout.
    // 50ms is a safe standard for a 150ms minimum election timeout.
    static final int HEARTBEAT_MILLIS = 50;
}