package dslabs.paxos;

import dslabs.atmostonce.AMOApplication;
import dslabs.framework.Address;
import dslabs.framework.Application;
import dslabs.framework.Command;
import dslabs.framework.Node;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class PaxosServer extends Node {
  private final Address[] servers;
  private final AMOApplication<?> app;

  // --- Raft Core State ---
  private enum Role {
    FOLLOWER, CANDIDATE, LEADER
  }

  private Address currentLeaderId = null;

  private Role role = Role.FOLLOWER;

  // Persistent state on all servers (in a real system, written to disk)
  private int currentTerm = 0;
  private Address votedFor = null;

  public record RaftLogEntry(int term, Command command) {
  }

  // Raft logs are 1-indexed. We initialize index 0 with a dummy entry.
  private final List<RaftLogEntry> raftLog = new ArrayList<>();

  // Volatile state on all servers
  private int commitIndex = 0;
  private int lastApplied = 0;

  // Volatile state on leaders (reinitialized after election)
  private final Map<Address, Integer> nextIndex = new HashMap<>();
  private final Map<Address, Integer> matchIndex = new HashMap<>();

  // Leader election tracking
  private int votesReceived = 0;

  /*
   * -----------------------------------------------------------------------------
   * ------------------
   * Construction and Initialization
   * -----------------------------------------------------------------------------
   * ----------------
   */
  public PaxosServer(Address address, Address[] servers, Application app) {
    super(address);
    this.servers = servers;
    this.app = new AMOApplication<>(app);

    // Initialize log with a dummy entry at index 0 to simplify math
    this.raftLog.add(new RaftLogEntry(0, null));
  }

  @Override
  public void init() {
    resetElectionTimer();
  }

  /*
   * -----------------------------------------------------------------------------
   * ------------------
   * Interface Methods (Bridging Raft to the Lab's requirements)
   * -----------------------------------------------------------------------------
   * ----------------
   */

  public PaxosLogSlotStatus status(int logSlotNum) {
    if (logSlotNum <= 0 || logSlotNum > raftLog.size() - 1) {
      return PaxosLogSlotStatus.EMPTY;
    }
    if (logSlotNum <= commitIndex) {
      return PaxosLogSlotStatus.CHOSEN;
    }
    return PaxosLogSlotStatus.ACCEPTED;
  }

  public Command command(int logSlotNum) {
    if (logSlotNum <= 0 || logSlotNum > raftLog.size() - 1) {
      return null;
    }
    Command cmd = raftLog.get(logSlotNum).command();
    if (cmd instanceof dslabs.atmostonce.AMOCommand) {
      return ((dslabs.atmostonce.AMOCommand) cmd).command();
    }
    return cmd;
  }

  public int firstNonCleared() {
    // Raft doesn't clear logs in this basic version without snapshots
    return 1;
  }

  public int lastNonEmpty() {
    return raftLog.size() - 1;
  }

  /*
   * -----------------------------------------------------------------------------
   * ------------------
   * Message Handlers
   * -----------------------------------------------------------------------------
   * ----------------
   */

  private void resetElectionTimer() {
    // Randomized timeout is essential to break ties in distributed systems.
    // Standard Raft range: 150ms to 300ms.
    int timeout = 150 + (int) (Math.random() * 150);
    set(new ElectionTimer(), timeout);
  }

  private void onElectionTimer(ElectionTimer t) {
    if (role == Role.LEADER) {
      return;
    }

    // Start Election
    role = Role.CANDIDATE;
    currentTerm++;
    votedFor = address();
    votesReceived = 1; // Vote for self
    resetElectionTimer();

    // Prepare RequestVote with last log metadata for the "up-to-date" check
    int lastLogIndex = raftLog.size() - 1;
    int lastLogTerm = raftLog.get(lastLogIndex).term();

    RequestVote rv = new RequestVote(currentTerm, address(), lastLogIndex, lastLogTerm);

    // Broadcast to all other servers
    for (Address server : servers) {
      if (!server.equals(address())) {
        send(rv, server);
      }
    }
  }

  private void handleRequestVote(RequestVote m, Address sender) {
    // 1. If term < currentTerm, reject vote
    if (m.term() < currentTerm) {
      send(new RequestVoteReply(currentTerm, false), sender);
      return;
    }

    // 2. If term > currentTerm, update term and revert to follower
    if (m.term() > currentTerm) {
      currentTerm = m.term();
      role = Role.FOLLOWER;
      votedFor = null;
    }

    // 3. Check Log Completeness (Safety Property)
    int lastLogIndex = raftLog.size() - 1;
    int lastLogTerm = raftLog.get(lastLogIndex).term();
    boolean logIsUpToDate = (m.lastLogTerm() > lastLogTerm) ||
        (m.lastLogTerm() == lastLogTerm && m.lastLogIndex() >= lastLogIndex);

    // 4. Grant vote if haven't voted yet or already voted for this candidate
    if ((votedFor == null || votedFor.equals(sender)) && logIsUpToDate) {
      votedFor = sender;
      resetElectionTimer(); // Reset timer when granting a vote to give the candidate a chance
      send(new RequestVoteReply(currentTerm, true), sender);
    } else {
      send(new RequestVoteReply(currentTerm, false), sender);
    }
  }

  private void handleRequestVoteReply(RequestVoteReply m, Address sender) {
    // Only Candidates care about vote replies
    if (role != Role.CANDIDATE) {
      return;
    }

    // If the voter has a higher term, we are deposed immediately
    if (m.term() > currentTerm) {
      currentTerm = m.term();
      role = Role.FOLLOWER;
      votedFor = null;
      resetElectionTimer();
      return;
    }

    // Tally the vote
    if (m.term() == currentTerm && m.voteGranted()) {
      votesReceived++;
      
      // Check for majority (including ourselves)
      if (votesReceived > servers.length / 2) {
        becomeLeader();
      }
    }
  }

  private void onHeartbeatTimer(HeartbeatTimer t) {
    // Only the leader sends heartbeats. If we were demoted, just ignore the timer.
    if (role == Role.LEADER) {
      broadcastAppendEntries();

      // Reset the timer to fire again
      set(t, HeartbeatTimer.HEARTBEAT_MILLIS);
    }
  }

  private void broadcastAppendEntries() {
    if (role != Role.LEADER)
      return;

    for (Address server : servers) {
      if (server.equals(address()))
        continue; // Don't send to self

      int nextIdx = nextIndex.get(server);
      int prevLogIndex = nextIdx - 1;
      int prevLogTerm = raftLog.get(prevLogIndex).term();

      // Extract any entries the follower is missing
      List<RaftLogEntry> entriesToSend = new ArrayList<>();
      for (int i = nextIdx; i < raftLog.size(); i++) {
        entriesToSend.add(raftLog.get(i));
      }

      AppendEntries ae = new AppendEntries(
          currentTerm,
          address(),
          prevLogIndex,
          prevLogTerm,
          entriesToSend,
          commitIndex);

      send(ae, server);
    }
  }

  private void handleAppendEntries(AppendEntries m, Address sender) {
    // 1. Reply false if term < currentTerm
    if (m.term() < currentTerm) {
      send(new AppendEntriesReply(currentTerm, false, 0), sender);
      return;
    }

    // 2. If term is valid, acknowledge the leader
    if (m.term() > currentTerm || role == Role.CANDIDATE) {
      currentTerm = m.term();
      role = Role.FOLLOWER;
      votedFor = null;
    }

    currentLeaderId = m.leaderId();

    // Always reset election timer when receiving a valid AppendEntries (Heartbeat)
    resetElectionTimer();

    // 3. Log Matching Property Check
    // If our log is too short, OR the term at prevLogIndex doesn't match the leader's prevLogTerm
    if (raftLog.size() - 1 < m.prevLogIndex() || 
        raftLog.get(m.prevLogIndex()).term() != m.prevLogTerm()) {
      
      // Reject and send back our actual log length to help the leader fast-backtrack
      send(new AppendEntriesReply(currentTerm, false, raftLog.size()), sender);
      return;
    }

    // 4. If an existing entry conflicts with a new one, delete the existing entry and all that follow it
    int insertIndex = m.prevLogIndex() + 1;
    for (RaftLogEntry entry : m.entries()) {
      if (insertIndex < raftLog.size()) {
        if (raftLog.get(insertIndex).term() != entry.term()) {
          // Conflict found! Truncate the log from this point forward
          raftLog.subList(insertIndex, raftLog.size()).clear();
          raftLog.add(entry);
        }
      } else {
        // No conflict, just append
        raftLog.add(entry);
      }
      insertIndex++;
    }

    // 5. Update commitIndex
    if (m.leaderCommit() > commitIndex) {
      commitIndex = Math.min(m.leaderCommit(), m.prevLogIndex() + m.entries().size());
      applyCommitted(); 
    }

    // Success! Tell the leader exactly what our highest log index is now.
    int highestAppendedIndex = m.prevLogIndex() + m.entries().size();
    send(new AppendEntriesReply(currentTerm, true, highestAppendedIndex), sender);
  }


  private void handleAppendEntriesReply(AppendEntriesReply m, Address sender) {
    if (role != Role.LEADER) {
      return;
    }

    // If a follower has a higher term, we are deposed.
    if (m.term() > currentTerm) {
      currentTerm = m.term();
      role = Role.FOLLOWER;
      votedFor = null;
      resetElectionTimer();
      return;
    }

    if (m.success()) {
      // The follower explicitly told us its matching index. No more guesswork!
      matchIndex.put(sender, m.matchIndex());
      nextIndex.put(sender, m.matchIndex() + 1);

      // Check if we can commit any new entries
      advanceCommitIndex();
    } else {
      // Fast Backtrack: Jump straight to the follower's actual log length.
      // If the follower's log was longer but had conflicts, it passes back raftLog.size(),
      // meaning nextIndex will step back just one index at a time until the terms match.
      int backtrackedIndex = m.matchIndex();
      
      // Ensure we never advance nextIndex on a failure, only decrement or hold.
      if (backtrackedIndex < nextIndex.get(sender)) {
          nextIndex.put(sender, Math.max(1, backtrackedIndex));
      } else {
          nextIndex.put(sender, nextIndex.get(sender) - 1);
      }
    }
  }

  
  // Helper method to check for quorums on replicated logs
  private void advanceCommitIndex() {
    // Find the highest log index 'N' such that a majority of servers have
    // matchIndex[i] >= N
    // and raftLog[N].term == currentTerm.
    for (int n = raftLog.size() - 1; n > commitIndex; n--) {
      if (raftLog.get(n).term() != currentTerm) {
        continue; // Raft rule: only commit entries from current term by counting replicas
      }

      int matchCount = 1; // Count ourselves
      for (Address server : servers) {
        if (!server.equals(address()) && matchIndex.get(server) >= n) {
          matchCount++;
        }
      }

      if (matchCount > servers.length / 2) {
        commitIndex = n;
        applyCommitted(); // Execute newly committed commands and reply to clients
        break; // Found the highest committable index, no need to check lower ones
      }
    }
  }




  private void handlePaxosRequest(PaxosRequest m, Address sender) {
        // 1. Gatekeeping & Redirection
        if (role != Role.LEADER) {
            // We are not the leader. Reply with the redirection metadata.
            // If an election is currently happening, currentLeaderId might be null.
            send(new PaxosReply(false, currentLeaderId, currentTerm), sender);
            return;
        }

        // 2. Append to local log
        raftLog.add(new RaftLogEntry(currentTerm, m.command()));
        int newEntryIndex = raftLog.size() - 1;
        
        // 3. Update the leader's own match and next indices
        matchIndex.put(address(), newEntryIndex);
        nextIndex.put(address(), newEntryIndex + 1);

        // 4. Replicate to followers
        broadcastAppendEntries();

        // Notice: We DO NOT execute the AMOApplication or send a PaxosReply here.
        // That happens in the advanceCommitIndex() method we wrote earlier, 
        // which gets triggered once handleAppendEntriesReply confirms a quorum.
    }


  private void becomeLeader() {
    role = Role.LEADER;
    currentLeaderId = address(); // <-- Fix: Update our own cache of the leader ID!
    nextIndex.clear();
    matchIndex.clear();

    for (Address server : servers) {
      nextIndex.put(server, raftLog.size());
      matchIndex.put(server, 0);
    }

    // Start sending heartbeats
    set(new HeartbeatTimer(), 50);
    broadcastAppendEntries();
  }

  private void applyCommitted() {
    while (lastApplied < commitIndex) {
      lastApplied++;
      Command cmd = raftLog.get(lastApplied).command();
      if (cmd != null) {
        dslabs.framework.Result res = app.execute(cmd);

        // If we are the leader, send the reply back to the client
        if (role == Role.LEADER && cmd instanceof dslabs.atmostonce.AMOCommand) {
          dslabs.atmostonce.AMOCommand amoCmd = (dslabs.atmostonce.AMOCommand) cmd;
          PaxosReply reply = new PaxosReply((dslabs.atmostonce.AMOResult) res);
          send(reply, amoCmd.clientId());
        }
      }
    }
  }

}