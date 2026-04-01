package dslabs.paxos;

import dslabs.framework.Address;
import dslabs.framework.Command;
import dslabs.framework.Message;
import java.io.Serializable;
import java.util.List;
import lombok.Data;

// Represents a single entry in the Raft log.
// Must be Serializable to be sent across the network.
@Data
public final class RaftLogEntry implements Serializable {
    private final int term;
    private final Command command;
}

/* -------------------------------------------------------------------------
 * AppendEntries (Log Replication & Heartbeats)
 * -----------------------------------------------------------------------*/

@Data
public final class AppendEntries implements Message {
    private final int term;                // Leader's current term
    private final Address leaderId;        // So followers can redirect clients
    private final int prevLogIndex;        // Index of log entry immediately preceding new ones
    private final int prevLogTerm;         // Term of prevLogIndex entry
    private final List<RaftLogEntry> entries; // Log entries to store (empty for heartbeat)
    private final int leaderCommit;        // Leader's commitIndex
}

@Data
public final class AppendEntriesReply implements Message {
    private final int term;
    private final boolean success;
    
    // On success: The index of the follower's highest log entry.
    // On failure: The length of the follower's log to help the leader backtrack.
    private final int matchIndex; 
}

/* -------------------------------------------------------------------------
 * RequestVote (Leader Election)
 * -----------------------------------------------------------------------*/

@Data
public final class RequestVote implements Message {
    private final int term;                // Candidate's term
    private final Address candidateId;     // Candidate requesting vote
    private final int lastLogIndex;        // Index of candidate's last log entry
    private final int lastLogTerm;         // Term of candidate's last log entry
}

@Data
public final class RequestVoteReply implements Message {
    private final int term;                // currentTerm, for candidate to update itself
    private final boolean voteGranted;     // true means candidate received vote
}