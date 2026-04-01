package dslabs.paxos;

import dslabs.atmostonce.AMOResult;
import dslabs.framework.Address;
import dslabs.framework.Message;
import lombok.Data;

@Data
public final class PaxosReply implements Message {
    private final AMOResult result;
    
    // Raft redirection metadata
    private final boolean isLeader;
    private final Address leaderId;
    private final int term;
    
    // Constructor for a successful execution (sent by the Leader)
    public PaxosReply(AMOResult result) {
        this.result = result;
        this.isLeader = true;
        this.leaderId = null;
        this.term = -1;
    }
    
    // Constructor for redirection (sent by a Follower or Candidate)
    public PaxosReply(boolean isLeader, Address leaderId, int term) {
        this.result = null;
        this.isLeader = isLeader;
        this.leaderId = leaderId;
        this.term = term;
    }
}