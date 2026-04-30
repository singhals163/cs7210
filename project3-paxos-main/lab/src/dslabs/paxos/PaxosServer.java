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
    private final AMOApplication<Application> app;

    private enum Role { FOLLOWER, CANDIDATE, LEADER }
    private Role role = Role.FOLLOWER;
    private Address currentLeaderId = null;

    private int currentTerm = 0;
    private Address votedFor = null;

    private final List<RaftLogEntry> raftLog = new ArrayList<>();
    private int commitIndex = 0;
    private int lastApplied = 0;

    private final Map<Address, Integer> nextIndex = new HashMap<>();
    private final Map<Address, Integer> matchIndex = new HashMap<>();
    private int votesReceived = 0;

    // --- Timer State ---
    private boolean heardFromLeader = false;

    // --- Garbage Collection State ---
    private int firstNonCleared = 1;
    private final Map<Address, Integer> followerLastApplied = new HashMap<>();

    public PaxosServer(Address address, Address[] servers, Application app) {
        super(address);
        this.servers = servers;
        this.app = new AMOApplication<>(app);
        this.raftLog.add(new RaftLogEntry(0, null));
    }

    @Override
    public void init() {
        // Bootstrap the single continuous timer loop
        set(new ElectionTimer(), 250 + (int) (Math.random() * 200));
    }

    // --- Required Interfaces ---

    public PaxosLogSlotStatus status(int logSlotNum) {
        if (logSlotNum < firstNonCleared) {
            return PaxosLogSlotStatus.CLEARED;
        }
        if (logSlotNum <= 0 || logSlotNum > raftLog.size() - 1) {
            return PaxosLogSlotStatus.EMPTY;
        }
        if (logSlotNum <= commitIndex) {
            return PaxosLogSlotStatus.CHOSEN;
        }
        return PaxosLogSlotStatus.ACCEPTED;
    }

    public Command command(int logSlotNum) {
        if (logSlotNum < firstNonCleared || logSlotNum <= 0 || logSlotNum > raftLog.size() - 1) {
            return null;
        }
        Command cmd = raftLog.get(logSlotNum).command();
        if (cmd instanceof dslabs.atmostonce.AMOCommand) {
            return ((dslabs.atmostonce.AMOCommand) cmd).command();
        }
        return cmd;
    }

    public int firstNonCleared() {
        return firstNonCleared;
    }

    public int lastNonEmpty() {
        return raftLog.size() - 1;
    }

    // --- Garbage Collection Logic ---

    private void garbageCollect(int newFirstNonCleared) {
        if (newFirstNonCleared > firstNonCleared) {
            for (int i = firstNonCleared; i < newFirstNonCleared; i++) {
                if (i < raftLog.size()) {
                    RaftLogEntry old = raftLog.get(i);
                    raftLog.set(i, new RaftLogEntry(old.term(), null));
                }
            }
            firstNonCleared = newFirstNonCleared;
        }
    }

    private void checkGarbageCollection() {
        if (role != Role.LEADER) return;
        
        int minApplied = lastApplied; 
        for (Address server : servers) {
            if (!server.equals(address())) {
                minApplied = Math.min(minApplied, followerLastApplied.getOrDefault(server, 0));
            }
        }
        
        if (minApplied > firstNonCleared) {
            garbageCollect(minApplied);
        }
    }

    // --- Timers and Core Handlers ---

    private void onElectionTimer(ElectionTimer t) {
        set(new ElectionTimer(), 250 + (int) (Math.random() * 200));

        if (role == Role.LEADER) return;

        if (heardFromLeader) {
            heardFromLeader = false;
            return;
        }

        role = Role.CANDIDATE;
        currentTerm++;
        votedFor = address();
        votesReceived = 1;

        int lastLogIndex = raftLog.size() - 1;
        int lastLogTerm = raftLog.get(lastLogIndex).term();

        RequestVote rv = new RequestVote(currentTerm, address(), lastLogIndex, lastLogTerm);
        for (Address server : servers) {
            if (!server.equals(address())) send(rv, server);
        }
    }

    private void handleRequestVote(RequestVote m, Address sender) {
        if (m.term() < currentTerm) {
            send(new RequestVoteReply(currentTerm, false), sender);
            return;
        }

        if (m.term() > currentTerm) {
            currentTerm = m.term();
            role = Role.FOLLOWER;
            votedFor = null;
        }

        int lastLogIndex = raftLog.size() - 1;
        int lastLogTerm = raftLog.get(lastLogIndex).term();
        
        boolean logIsUpToDate = (m.lastLogTerm() > lastLogTerm) ||
            (m.lastLogTerm() == lastLogTerm && m.lastLogIndex() >= lastLogIndex);

        if ((votedFor == null || votedFor.equals(sender)) && logIsUpToDate) {
            votedFor = sender;
            heardFromLeader = true; 
            send(new RequestVoteReply(currentTerm, true), sender);
        } else {
            send(new RequestVoteReply(currentTerm, false), sender);
        }
    }

    private void handleRequestVoteReply(RequestVoteReply m, Address sender) {
        if (role != Role.CANDIDATE) return;

        if (m.term() > currentTerm) {
            currentTerm = m.term();
            role = Role.FOLLOWER;
            votedFor = null;
            return;
        }

        if (m.term() == currentTerm && m.voteGranted()) {
            votesReceived++;
            if (votesReceived > servers.length / 2) {
                becomeLeader();
            }
        }
    }

    private void onHeartbeatTimer(HeartbeatTimer t) {
        if (role == Role.LEADER) {
            broadcastAppendEntries();
            set(t, HeartbeatTimer.HEARTBEAT_MILLIS);
        }
    }

    private void sendAppendEntries(Address server) {
        int nextIdx = nextIndex.getOrDefault(server, 1);
        int prevLogIndex = nextIdx - 1;
        int prevLogTerm = raftLog.get(prevLogIndex).term();

        List<RaftLogEntry> entriesToSend = new ArrayList<>();
        for (int i = nextIdx; i < raftLog.size(); i++) {
            entriesToSend.add(raftLog.get(i));
        }

        AppendEntries ae = new AppendEntries(
            currentTerm, address(), prevLogIndex, prevLogTerm, entriesToSend, commitIndex, firstNonCleared
        );
        send(ae, server);
    }

    private void broadcastAppendEntries() {
        if (role != Role.LEADER) return;
        for (Address server : servers) {
            if (!server.equals(address())) {
                sendAppendEntries(server);
            }
        }
    }

    private void handleAppendEntries(AppendEntries m, Address sender) {
        if (m.term() < currentTerm) {
            send(new AppendEntriesReply(currentTerm, false, 0, lastApplied), sender);
            return;
        }

        if (m.term() > currentTerm || role == Role.CANDIDATE) {
            currentTerm = m.term();
            role = Role.FOLLOWER;
            votedFor = null;
        }

        currentLeaderId = m.leaderId();
        heardFromLeader = true;

        if (m.globalFirstNonCleared() > firstNonCleared) {
            garbageCollect(m.globalFirstNonCleared());
        }

        if (raftLog.size() - 1 < m.prevLogIndex() ||
            raftLog.get(m.prevLogIndex()).term() != m.prevLogTerm()) {
            send(new AppendEntriesReply(currentTerm, false, raftLog.size() - 1, lastApplied), sender);
            return;
        }

        int insertIndex = m.prevLogIndex() + 1;
        for (RaftLogEntry entry : m.entries()) {
            if (insertIndex < raftLog.size()) {
                if (raftLog.get(insertIndex).term() != entry.term()) {
                    raftLog.subList(insertIndex, raftLog.size()).clear();
                    raftLog.add(entry);
                }
            } else {
                raftLog.add(entry);
            }
            insertIndex++;
        }

        if (m.leaderCommit() > commitIndex) {
            int potentialCommit = Math.min(m.leaderCommit(), m.prevLogIndex() + m.entries().size());
            if (potentialCommit > commitIndex) {
                commitIndex = potentialCommit;
                applyCommitted();
            }
        }

        int highestAppendedIndex = m.prevLogIndex() + m.entries().size();
        send(new AppendEntriesReply(currentTerm, true, highestAppendedIndex, lastApplied), sender);
    }

    private void handleAppendEntriesReply(AppendEntriesReply m, Address sender) {
        if (role != Role.LEADER) return;

        if (m.term() > currentTerm) {
            currentTerm = m.term();
            role = Role.FOLLOWER;
            votedFor = null;
            return;
        }

        followerLastApplied.put(sender, m.lastApplied());
        checkGarbageCollection();

        if (m.success()) {
            if (m.matchIndex() > matchIndex.getOrDefault(sender, 0)) {
                matchIndex.put(sender, m.matchIndex());
                nextIndex.put(sender, m.matchIndex() + 1);
                advanceCommitIndex();
            }
            
            if (nextIndex.get(sender) < raftLog.size()) {
                sendAppendEntries(sender);
            }
        } else {
            int backtrackedIndex = m.matchIndex();
            if (backtrackedIndex < nextIndex.get(sender)) {
                nextIndex.put(sender, Math.max(1, backtrackedIndex));
            } else {
                nextIndex.put(sender, Math.max(1, nextIndex.get(sender) - 1));
            }
            
            sendAppendEntries(sender);
        }
    }

    private void advanceCommitIndex() {
        for (int n = raftLog.size() - 1; n > commitIndex; n--) {
            if (raftLog.get(n).term() != currentTerm) continue;

            int matchCount = 1;
            for (Address server : servers) {
                if (!server.equals(address()) && matchIndex.getOrDefault(server, 0) >= n) {
                    matchCount++;
                }
            }

            if (matchCount > servers.length / 2) {
                commitIndex = n;
                applyCommitted();
                break;
            }
        }
    }

    private void handlePaxosRequest(PaxosRequest m, Address sender) {
        if (role != Role.LEADER) {
            send(new PaxosReply(false, currentLeaderId, currentTerm), sender);
            return;
        }

        if (m.command() instanceof dslabs.atmostonce.AMOCommand) {
            dslabs.atmostonce.AMOCommand amoCmd = (dslabs.atmostonce.AMOCommand) m.command();
            
            if (app.alreadyExecuted(amoCmd)) {
                send(new PaxosReply((dslabs.atmostonce.AMOResult) app.execute(amoCmd)), sender);
                return;
            }
            
            boolean isPending = false;
            for (int i = raftLog.size() - 1; i > commitIndex; i--) {
                Command pendingCmd = raftLog.get(i).command();
                if (pendingCmd != null && pendingCmd.equals(amoCmd)) {
                    isPending = true;
                    break;
                }
            }
            if (isPending) {
                broadcastAppendEntries();
                return; 
            }
        }

        raftLog.add(new RaftLogEntry(currentTerm, m.command()));
        int newEntryIndex = raftLog.size() - 1;
        
        matchIndex.put(address(), newEntryIndex);
        nextIndex.put(address(), newEntryIndex + 1);

        broadcastAppendEntries();
    }

    private void becomeLeader() {
        role = Role.LEADER;
        currentLeaderId = address();
        nextIndex.clear();
        matchIndex.clear();
        followerLastApplied.clear();

        for (Address server : servers) {
            nextIndex.put(server, raftLog.size());
            matchIndex.put(server, 0);
            followerLastApplied.put(server, 0);
        }
        
        followerLastApplied.put(address(), lastApplied);

        set(new HeartbeatTimer(), HeartbeatTimer.HEARTBEAT_MILLIS);
        broadcastAppendEntries();
    }

    private void applyCommitted() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            Command cmd = raftLog.get(lastApplied).command();
            if (cmd != null) {
                dslabs.framework.Result res = app.execute(cmd);

                if (role == Role.LEADER && cmd instanceof dslabs.atmostonce.AMOCommand) {
                    dslabs.atmostonce.AMOCommand amoCmd = (dslabs.atmostonce.AMOCommand) cmd;
                    PaxosReply reply = new PaxosReply((dslabs.atmostonce.AMOResult) res);
                    send(reply, amoCmd.clientId());
                }
            }
        }
        
        if (role == Role.LEADER) {
            followerLastApplied.put(address(), lastApplied);
            checkGarbageCollection();
        }
    }
}