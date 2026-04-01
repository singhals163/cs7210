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

    private enum Role {
        FOLLOWER, CANDIDATE, LEADER
    }

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

    public PaxosServer(Address address, Address[] servers, Application app) {
        super(address);
        this.servers = servers;
        this.app = new AMOApplication<>(app);
        this.raftLog.add(new RaftLogEntry(0, null));
    }

    @Override
    public void init() {
        resetElectionTimer();
    }

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
        return 1;
    }

    public int lastNonEmpty() {
        return raftLog.size() - 1;
    }

    private void resetElectionTimer() {
        int timeout = 150 + (int) (Math.random() * 150);
        set(new ElectionTimer(), timeout);
    }

    private void onElectionTimer(ElectionTimer t) {
        if (role == Role.LEADER)
            return;

        role = Role.CANDIDATE;
        currentTerm++;
        votedFor = address();
        votesReceived = 1;
        resetElectionTimer();

        int lastLogIndex = raftLog.size() - 1;
        int lastLogTerm = raftLog.get(lastLogIndex).term();

        RequestVote rv = new RequestVote(currentTerm, address(), lastLogIndex, lastLogTerm);
        for (Address server : servers) {
            if (!server.equals(address())) {
                send(rv, server);
            }
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
            resetElectionTimer();
            send(new RequestVoteReply(currentTerm, true), sender);
        } else {
            send(new RequestVoteReply(currentTerm, false), sender);
        }
    }

    private void handleRequestVoteReply(RequestVoteReply m, Address sender) {
        if (role != Role.CANDIDATE)
            return;

        if (m.term() > currentTerm) {
            currentTerm = m.term();
            role = Role.FOLLOWER;
            votedFor = null;
            resetElectionTimer();
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

    private void broadcastAppendEntries() {
        if (role != Role.LEADER)
            return;

        for (Address server : servers) {
            if (server.equals(address()))
                continue;

            int nextIdx = nextIndex.get(server);
            int prevLogIndex = nextIdx - 1;
            int prevLogTerm = raftLog.get(prevLogIndex).term();

            List<RaftLogEntry> entriesToSend = new ArrayList<>();
            for (int i = nextIdx; i < raftLog.size(); i++) {
                entriesToSend.add(raftLog.get(i));
            }

            AppendEntries ae = new AppendEntries(
                    currentTerm, address(), prevLogIndex, prevLogTerm, entriesToSend, commitIndex);
            send(ae, server);
        }
    }

    private void handleAppendEntries(AppendEntries m, Address sender) {
        if (m.term() < currentTerm) {
            send(new AppendEntriesReply(currentTerm, false, 0), sender);
            return;
        }

        if (m.term() > currentTerm || role == Role.CANDIDATE) {
            currentTerm = m.term();
            role = Role.FOLLOWER;
            votedFor = null;
        }

        currentLeaderId = m.leaderId();
        resetElectionTimer();

        if (raftLog.size() - 1 < m.prevLogIndex() ||
                raftLog.get(m.prevLogIndex()).term() != m.prevLogTerm()) {
            send(new AppendEntriesReply(currentTerm, false, raftLog.size()), sender);
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
        send(new AppendEntriesReply(currentTerm, true, highestAppendedIndex), sender);
    }

    private void handleAppendEntriesReply(AppendEntriesReply m, Address sender) {
        if (role != Role.LEADER)
            return;

        if (m.term() > currentTerm) {
            currentTerm = m.term();
            role = Role.FOLLOWER;
            votedFor = null;
            resetElectionTimer();
            return;
        }

        if (m.success()) {
            if (m.matchIndex() > matchIndex.get(sender)) {
                matchIndex.put(sender, m.matchIndex());
                nextIndex.put(sender, m.matchIndex() + 1);
                advanceCommitIndex();
            }
        } else {
            int backtrackedIndex = m.matchIndex();
            if (backtrackedIndex < nextIndex.get(sender)) {
                nextIndex.put(sender, Math.max(1, backtrackedIndex));
            }
        }
    }

    private void advanceCommitIndex() {
        for (int n = raftLog.size() - 1; n > commitIndex; n--) {
            if (raftLog.get(n).term() != currentTerm)
                continue;

            int matchCount = 1;
            for (Address server : servers) {
                if (!server.equals(address()) && matchIndex.get(server) >= n) {
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

        for (Address server : servers) {
            nextIndex.put(server, raftLog.size());
            matchIndex.put(server, 0);
        }

        set(new HeartbeatTimer(), 50);
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
    }
}