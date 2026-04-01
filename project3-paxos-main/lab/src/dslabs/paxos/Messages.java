package dslabs.paxos;

import dslabs.framework.Address;
import dslabs.framework.Command;
import dslabs.framework.Message;
import java.io.Serializable;
import java.util.List;
import lombok.Data;

@Data
final class RaftLogEntry implements Serializable {
    private final int term;
    private final Command command;
}

@Data
final class AppendEntries implements Message {
    private final int term;
    private final Address leaderId;
    private final int prevLogIndex;
    private final int prevLogTerm;
    private final List<RaftLogEntry> entries;
    private final int leaderCommit;
}

@Data
final class AppendEntriesReply implements Message {
    private final int term;
    private final boolean success;
    private final int matchIndex;
}

@Data
final class RequestVote implements Message {
    private final int term;
    private final Address candidateId;
    private final int lastLogIndex;
    private final int lastLogTerm;
}

@Data
final class RequestVoteReply implements Message {
    private final int term;
    private final boolean voteGranted;
}