package dslabs.shardkv;

import dslabs.framework.Address;
import dslabs.framework.Application;
import dslabs.framework.Command;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import dslabs.shardmaster.ShardMaster.Query;
import dslabs.shardmaster.ShardMaster.ShardConfig;

import dslabs.atmostonce.AMOApplication;
import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import dslabs.paxos.PaxosDecision;
import dslabs.paxos.PaxosReply;
import dslabs.paxos.PaxosRequest;
import dslabs.paxos.PaxosServer;

import dslabs.kvstore.KVStore.KVStoreResult;
import dslabs.kvstore.KVStore.SingleKeyCommand;
import dslabs.kvstore.TransactionalKVStore;
import dslabs.kvstore.TransactionalKVStore.MultiGet;
import dslabs.kvstore.TransactionalKVStore.MultiGetResult;
import dslabs.kvstore.TransactionalKVStore.MultiPut;
import dslabs.kvstore.TransactionalKVStore.MultiPutOk;
import dslabs.kvstore.TransactionalKVStore.Swap;
import dslabs.kvstore.TransactionalKVStore.SwapOk;
import dslabs.kvstore.TransactionalKVStore.Transaction;

import org.apache.commons.lang3.tuple.Pair;

import static dslabs.shardkv.PingTimer.PING_RETRY_MILLIS;
import static dslabs.shardkv.MoveTimer.MOVE_RETRY_MILLIS;
import static dslabs.shardkv.PrepareTimer.PREPARE_RETRY_MILLIS;
import static dslabs.shardkv.CommitTimer.COMMIT_RETRY_MILLIS;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class ShardStoreServer extends ShardStoreNode {
  private final Address[] group;
  private final int groupId;

  // ---------- paxos sub-node config ----------
  private static final String PAXOS_ADDRESS_ID = "paxos";
  private static final String PAXOS_PING_ID = "paxos-ping";
  private Address paxosAddress;

  // ---------- shard state ----------
  private Map<Integer, AMOApplication<Application>> app;
  private Map<Integer, Pair<Set<Address>, Set<Integer>>> currentConfig;
  private Integer currentConfigNum = -1;
  private Set<Integer> currentManagedShards;

  // ---------- transaction state ----------

  // Server-level lock with owner identity. The lock is held by:
  //   - the coordinator: from admit until COMMIT/ABORT phase finishes
  //   - a participant: from voting YES on PREPARE until receiving COMMIT/ABORT
  // Re-entrant for the same AMOCommand so duplicate messages are idempotent.
  private AMOCommand lockHolder = null;

  // Coordinator-side AMO cache: (clientAddr) -> last-seen finished result.
  // Used to short-circuit retries from the client of an already-completed txn.
  private Map<Address, AMOResult> txnAmoCache = new HashMap<>();

  // Participant-side cache: (clientAddr) -> last partial result we returned
  // for a COMMIT, so retried COMMITs are idempotent (don't re-execute against
  // our store).
  private Map<Address, AMOResult> participantTxnCache = new HashMap<>();

  // Coordinator's per-transaction state (only one active txn at a time;
  // the lock above guarantees that).
  private enum TxnPhase { IDLE, PREPARING, COMMITTING, ABORTING }
  private TxnPhase txnPhase = TxnPhase.IDLE;
  private AMOCommand currentTxn = null;
  private Set<Integer> txnParticipants = null;     // groups whose votes we need
  private Set<Integer> txnPrepareYes = null;       // groups that voted YES (incl. self)
  private Set<Integer> txnAcks = null;             // groups that ACKed COMMIT/ABORT (incl. self)
  private Map<Integer, KVStoreResult> txnPartials = null;

  /*
   * -----------------------------------------------------------------------------
   * Construction and Initialization
   * -----------------------------------------------------------------------------
   */
  ShardStoreServer(
      Address address, Address[] shardMasters, int numShards, Address[] group, int groupId) {
    super(address, shardMasters, numShards);
    this.group = group;
    this.groupId = groupId;

    this.app = new HashMap<>();
    this.currentManagedShards = new HashSet<>();
  }

  @Override
  public void init() {
    paxosAddress = Address.subAddress(address(), PAXOS_ADDRESS_ID);

    Address[] paxosAddresses = new Address[group.length];
    for (int i = 0; i < paxosAddresses.length; i++) {
      paxosAddresses[i] = Address.subAddress(group[i], PAXOS_ADDRESS_ID);
    }

    PaxosServer paxosServer = new PaxosServer(paxosAddress, paxosAddresses, address());
    addSubNode(paxosServer);
    paxosServer.init();

    set(new PingTimer(), PING_RETRY_MILLIS);
  }

  /*
   * -----------------------------------------------------------------------------
   * Top-level dispatch
   * -----------------------------------------------------------------------------
   */
  private void handleShardStoreRequest(ShardStoreRequest m, Address sender) {
    Command inner = m.command().command();
    if (inner instanceof SingleKeyCommand) {
      handleShardStoreSingleKeyRequest(m, sender);
    } else if (inner instanceof Transaction) {
      handleShardStoreCoordinator(m, sender);
    }
  }

  /*
   * -----------------------------------------------------------------------------
   * SingleKey path (unchanged from part 3)
   * -----------------------------------------------------------------------------
   */
  private void handleShardStoreSingleKeyRequest(ShardStoreRequest m, Address sender) {
    AMOCommand command = m.command();
    if (m.configNum() != currentConfigNum) {
      send(new ShardStoreReply(currentConfigNum, null), command.address());
      if (m.configNum() > currentConfigNum) {
        sendConfigRequest(currentConfigNum + 1);
      }
      return;
    }
    if (currentConfig == null || !currentConfig.containsKey(groupId)) {
      send(new ShardStoreReply(currentConfigNum, null), command.address());
      return;
    }
    Integer shardId = keyToShard(m.key());
    if (!currentConfig.get(groupId).getRight().contains(shardId)
        || !currentManagedShards.contains(shardId)) {
      send(new ShardStoreReply(currentConfigNum, null), command.address());
      return;
    }

    AMOApplication<Application> shardApp = app.get(shardId);
    if (shardApp.alreadyExecuted(command)) {
      AMOResult result = shardApp.execute(command);
      send(new ShardStoreReply(currentConfigNum, result), command.address());
      return;
    }
    handleMessage(new PaxosRequest(sender.toString() + "-" + currentConfigNum,
        command.sequenceNumber(), new ShardStoreCommand(m)), paxosAddress);
  }

  /*
   * -----------------------------------------------------------------------------
   * Transaction coordinator
   * -----------------------------------------------------------------------------
   */

  // Thin wrapper: route the client's transaction request through local Paxos
  // so every replica in the coordinator group admits the same txn at the same
  // log slot.  AMO cache fast-path stays here (avoids paxos for retries).
  private void handleShardStoreCoordinator(ShardStoreRequest m, Address sender) {
    AMOCommand command = m.command();
    AMOResult cached = txnAmoCache.get(command.address());
    if (cached != null && cached.sequenceNumber() >= command.sequenceNumber()) {
      send(new ShardStoreReply(currentConfigNum, cached), command.address());
      return;
    }
    String id = "txnClient-" + command.address() + "-" + currentConfigNum;
    handleMessage(new PaxosRequest(id, command.sequenceNumber(),
        new TxnClientReqCmd(m)), paxosAddress);
  }

  // Replicated body: runs on every replica in the same paxos-decided order.
  private void processShardStoreCoordinator(ShardStoreRequest m) {
    AMOCommand command = m.command();
    Transaction txn = (Transaction) command.command();

    if (!coordinatorPreChecksPass(m, command, txn)) return;

    AMOResult cached = txnAmoCache.get(command.address());
    if (cached != null && cached.sequenceNumber() >= command.sequenceNumber()) {
      send(new ShardStoreReply(currentConfigNum, cached), command.address());
      return;
    }

    if (currentTxn != null && currentTxn.equals(command)) {
      return;
    }

    if (!tryLock(command)) {
      send(new ShardStoreReply(currentConfigNum, null), command.address());
      return;
    }

    Set<Integer> participants = participantsForTransaction(txn);

    // Single-group fast path: skip 2PC.
    if (participants.size() == 1) {
      KVStoreResult result = executeTransactionOnOwnedShards(txn);
      AMOResult amoResult = new AMOResult(command.sequenceNumber(), result);
      txnAmoCache.put(command.address(), amoResult);
      releaseLock(command);
      send(new ShardStoreReply(currentConfigNum, amoResult), command.address());
      return;
    }

    // Multi-group: kick off 2PC.
    beginPreparePhase(command, participants);
  }

  // True when this server should accept being coordinator for the given txn.
  // Replies null and returns false otherwise.
  private boolean coordinatorPreChecksPass(
      ShardStoreRequest m, AMOCommand command, Transaction txn) {
    if (m.configNum() != currentConfigNum) {
      send(new ShardStoreReply(currentConfigNum, null), command.address());
      if (m.configNum() > currentConfigNum) {
        sendConfigRequest(currentConfigNum + 1);
      }
      return false;
    }
    if (currentConfig == null || !currentConfig.containsKey(groupId) || !isStable()) {
      send(new ShardStoreReply(currentConfigNum, null), command.address());
      return false;
    }
    // Every key whose shard we own must be settled.
    for (String key : txn.keySet()) {
      int shardId = keyToShard(key);
      if (currentConfig.get(groupId).getRight().contains(shardId)
          && !currentManagedShards.contains(shardId)) {
        send(new ShardStoreReply(currentConfigNum, null), command.address());
        return false;
      }
    }
    Set<Integer> participants = participantsForTransaction(txn);
    if (participants.isEmpty() || groupId != Collections.min(participants)) {
      send(new ShardStoreReply(currentConfigNum, null), command.address());
      return false;
    }
    return true;
  }

  // Phase 1: send PREPARE to every other participant; we count ourselves as
  // already-prepared because we already hold the lock from handleShardStoreCoordinator.
  private void beginPreparePhase(AMOCommand command, Set<Integer> participants) {
    txnPhase = TxnPhase.PREPARING;
    currentTxn = command;
    txnParticipants = participants;
    txnPrepareYes = new HashSet<>();
    txnPrepareYes.add(groupId);
    txnAcks = new HashSet<>();
    txnPartials = new HashMap<>();

    for (Integer other : participants) {
      if (other == groupId) continue;
      Address[] dest = currentConfig.get(other).getLeft().toArray(new Address[0]);
      PrepareTransactionRequest req =
          new PrepareTransactionRequest(currentConfigNum, command, group);
      broadcast(req, dest);
      set(new PrepareTimer(dest, req), PREPARE_RETRY_MILLIS);
    }
  }

  private void handlePrepareTransactionReply(PrepareTransactionReply m, Address sender) {
    String id = "txnPrepReply-" + m.command().address() + "-" + m.command().sequenceNumber()
        + "-" + m.groupId() + "-" + currentConfigNum;
    handleMessage(new PaxosRequest(id, currentConfigNum,
        new TxnPrepareReplyCmd(m)), paxosAddress);
  }

  private void processPrepareTransactionReply(PrepareTransactionReply m) {
    if (txnPhase != TxnPhase.PREPARING || currentTxn == null) return;
    if (!currentTxn.equals(m.command())) return;          // stale reply
    if (m.configNum() != currentConfigNum) return;

    if (m.result()) {
      txnPrepareYes.add(m.groupId());
      if (txnPrepareYes.equals(txnParticipants)) {
        beginCommitPhase();
      }
    } else {
      beginAbortPhase();
    }
  }

  // Phase 2 (commit): execute locally now (we have all YES votes), then send
  // COMMIT to other participants.  Coordinator's own partial result is saved
  // immediately so the merge in finishCommit always has it.
  private void beginCommitPhase() {
    Transaction txn = (Transaction) currentTxn.command();
    KVStoreResult ourPartial = executeTransactionOnOwnedShards(txn);
    txnPartials.put(groupId, ourPartial);

    txnPhase = TxnPhase.COMMITTING;
    txnAcks.add(groupId);

    for (Integer other : txnParticipants) {
      if (other == groupId) continue;
      Address[] dest = currentConfig.get(other).getLeft().toArray(new Address[0]);
      CommitTransactionRequest req =
          new CommitTransactionRequest(currentConfigNum, currentTxn, group, true);
      broadcast(req, dest);
      set(new CommitTimer(dest, req), COMMIT_RETRY_MILLIS);
    }

    // Could be done already if we're the only "yes" group (shouldn't happen
    // after the single-group fast path, but defensively):
    if (txnAcks.equals(txnParticipants)) finishCommit();
  }

  // Phase 2 (abort): at least one NO vote came in.  Send ABORT to every
  // participant (not just the YES voters) — a slow PREPARE could arrive at a
  // participant after we decided to abort, and we want that participant's
  // eventual lock to get released.  ABORT is idempotent at the participant.
  // Reply null to the client immediately so it can retry.
  private void beginAbortPhase() {
    txnPhase = TxnPhase.ABORTING;
    txnAcks = new HashSet<>();
    txnAcks.add(groupId);

    for (Integer other : txnParticipants) {
      if (other == groupId) continue;
      Address[] dest = currentConfig.get(other).getLeft().toArray(new Address[0]);
      CommitTransactionRequest req =
          new CommitTransactionRequest(currentConfigNum, currentTxn, group, false);
      broadcast(req, dest);
      set(new CommitTimer(dest, req), COMMIT_RETRY_MILLIS);
    }

    send(new ShardStoreReply(currentConfigNum, null), currentTxn.address());

    if (txnAcks.equals(txnParticipants)) {
      finishAbort();
    }
  }

  private void handleCommitTransactionReply(CommitTransactionReply m, Address sender) {
    String id = "txnCommitReply-" + m.command().address() + "-" + m.command().sequenceNumber()
        + "-" + m.groupId() + "-" + currentConfigNum;
    handleMessage(new PaxosRequest(id, currentConfigNum,
        new TxnCommitReplyCmd(m)), paxosAddress);
  }

  private void processCommitTransactionReply(CommitTransactionReply m) {
    if (currentTxn == null) return;
    if (!currentTxn.equals(m.command())) return;          // stale reply
    if (m.configNum() != currentConfigNum) return;
    if (txnPhase != TxnPhase.COMMITTING && txnPhase != TxnPhase.ABORTING) return;

    txnAcks.add(m.groupId());
    if (txnPhase == TxnPhase.COMMITTING && m.partialResult() != null) {
      txnPartials.put(m.groupId(), m.partialResult());
    }

    if (txnAcks.equals(txnParticipants)) {
      if (txnPhase == TxnPhase.COMMITTING) finishCommit();
      else finishAbort();
    }
  }

  private void finishCommit() {
    Transaction txn = (Transaction) currentTxn.command();
    KVStoreResult finalResult = mergePartials(txn, txnPartials);
    AMOResult amoResult = new AMOResult(currentTxn.sequenceNumber(), finalResult);
    Address clientAddr = currentTxn.address();
    AMOCommand cmd = currentTxn;

    txnAmoCache.put(clientAddr, amoResult);
    clearTxnState();
    releaseLock(cmd);
    send(new ShardStoreReply(currentConfigNum, amoResult), clientAddr);
  }

  private void finishAbort() {
    AMOCommand cmd = currentTxn;
    clearTxnState();
    releaseLock(cmd);
    // The null reply was already sent at the start of beginAbortPhase.
    // Nothing further to do; lock is now free for the client's next retry.
  }

  private void clearTxnState() {
    txnPhase = TxnPhase.IDLE;
    currentTxn = null;
    txnParticipants = null;
    txnPrepareYes = null;
    txnAcks = null;
    txnPartials = null;
  }

  /*
   * -----------------------------------------------------------------------------
   * Transaction participant
   * -----------------------------------------------------------------------------
   */

  // Thin wrapper: route the PREPARE through paxos so all replicas in the
  // participant group take the same vote.  AMO cache fast-path stays here for
  // already-committed retries.
  private void handlePrepareTransactionRequest(PrepareTransactionRequest m, Address sender) {
    AMOCommand command = m.command();
    AMOResult cached = participantTxnCache.get(command.address());
    if (cached != null && cached.sequenceNumber() >= command.sequenceNumber()) {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, true, groupId), m.senders());
      return;
    }
    String id = "txnPrep-" + command.address() + "-" + command.sequenceNumber()
        + "-" + currentConfigNum;
    handleMessage(new PaxosRequest(id, currentConfigNum,
        new TxnPrepareReqCmd(m)), paxosAddress);
  }

  private void processPrepareTransactionRequest(PrepareTransactionRequest m) {
    AMOCommand command = m.command();
    Transaction txn = (Transaction) command.command();

    if (m.configNum() != currentConfigNum) {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, false, groupId), m.senders());
      if (m.configNum() > currentConfigNum) {
        sendConfigRequest(currentConfigNum + 1);
      }
      return;
    }
    if (currentConfig == null || !currentConfig.containsKey(groupId) || !isStable()) {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, false, groupId), m.senders());
      return;
    }

    // We must own at least one shard from the txn's key set.
    boolean involved = false;
    for (String key : txn.keySet()) {
      if (currentManagedShards.contains(keyToShard(key))) {
        involved = true;
        break;
      }
    }
    if (!involved) {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, false, groupId), m.senders());
      return;
    }

    // Already executed (commit replied earlier and ack lost): just say YES
    // again; the coordinator's COMMIT retry will hit our cache.
    AMOResult cached = participantTxnCache.get(command.address());
    if (cached != null && cached.sequenceNumber() >= command.sequenceNumber()) {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, true, groupId), m.senders());
      return;
    }

    if (tryLock(command)) {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, true, groupId), m.senders());
    } else {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, false, groupId), m.senders());
    }
  }

  // Thin wrapper: route the COMMIT/ABORT through paxos so all replicas in the
  // participant group apply the transaction's writes (or release the lock) at
  // the same paxos slot.  Cache fast-path replies straight away on a duplicate
  // COMMIT for an already-committed transaction.
  private void handleCommitTransactionRequest(CommitTransactionRequest m, Address sender) {
    AMOCommand command = m.command();
    if (m.commit()) {
      AMOResult cached = participantTxnCache.get(command.address());
      if (cached != null && cached.sequenceNumber() >= command.sequenceNumber()) {
        broadcast(new CommitTransactionReply(
            currentConfigNum, command, true, groupId,
            (KVStoreResult) cached.result()), m.senders());
        return;
      }
    }
    String id = "txnCommit-" + command.address() + "-" + command.sequenceNumber()
        + "-" + currentConfigNum + "-" + (m.commit() ? "C" : "A");
    handleMessage(new PaxosRequest(id, currentConfigNum,
        new TxnCommitReqCmd(m)), paxosAddress);
  }

  private void processCommitTransactionRequest(CommitTransactionRequest m) {
    AMOCommand command = m.command();

    if (m.configNum() != currentConfigNum) {
      // Stale config — just ack so the coordinator can drop us.
      broadcast(new CommitTransactionReply(
          currentConfigNum, command, m.commit(), groupId, null), m.senders());
      return;
    }

    if (m.commit()) {
      // Idempotent commit via cache.
      AMOResult cached = participantTxnCache.get(command.address());
      if (cached != null && cached.sequenceNumber() >= command.sequenceNumber()) {
        broadcast(new CommitTransactionReply(
            currentConfigNum, command, true, groupId, (KVStoreResult)cached.result()), m.senders());
        return;
      }
      if (command.equals(lockHolder)) {
        Transaction txn = (Transaction) command.command();
        KVStoreResult partial = executeTransactionOnOwnedShards(txn);
        AMOResult amoResult = new AMOResult(command.sequenceNumber(), partial);
        participantTxnCache.put(command.address(), amoResult);
        releaseLock(command);
        broadcast(new CommitTransactionReply(
            currentConfigNum, command, true, groupId, partial), m.senders());
        return;
      }
      // Defensive: not in cache, not holding the lock — just ack with no
      // result; the coordinator's merge tolerates absence of our partial.
      broadcast(new CommitTransactionReply(
          currentConfigNum, command, true, groupId, null), m.senders());
    } else {
      if (command.equals(lockHolder)) {
        releaseLock(command);
      }
      broadcast(new CommitTransactionReply(
          currentConfigNum, command, false, groupId, null), m.senders());
    }
  }

  /*
   * -----------------------------------------------------------------------------
   * Move/paxos handlers (unchanged from part 3)
   * -----------------------------------------------------------------------------
   */
  private void handleMoveRequest(MoveRequest m, Address sender) {
    if (m.configNum() > currentConfigNum) {
      sendConfigRequest(currentConfigNum + 1);
      return;
    }
    if (m.configNum() < currentConfigNum) {
      broadcast(new MoveReply(m.configNum(), m.shardId()), m.senders());
      return;
    }
    if (currentConfig.containsKey(groupId) && currentConfig.get(groupId).getRight().contains(m.shardId())) {
      if (!currentManagedShards.contains(m.shardId())) {
        handleMessage(new PaxosRequest("shardMove-" + m.shardId() + "-" + m.configNum(),
            m.configNum(), new ShardMoveCmd(m)), paxosAddress);
      } else {
        broadcast(new MoveReply(currentConfigNum, m.shardId()), m.senders());
      }
    }
  }

  private void handleMoveReply(MoveReply m, Address sender) {
    if (m.configNum() > currentConfigNum) {
      sendConfigRequest(currentConfigNum + 1);
    }
    if (m.configNum() != currentConfigNum) return;
    if (currentManagedShards.contains(m.shardId())) {
      handleMessage(new PaxosRequest("shardMoveAck-" + m.shardId() + "-" + m.configNum(),
          m.configNum(), new ShardMoveAckCmd(m)), paxosAddress);
    }
  }

  void handlePaxosReply(PaxosReply m, Address sender) {
    if (!PAXOS_PING_ID.equals(m.id())) return;
    if (m.result() instanceof ShardConfig) {
      ShardConfig newConfig = (ShardConfig) m.result();
      if (newConfig.configNum() == currentConfigNum + 1 && isStable()) {
        handleMessage(new PaxosRequest("newConfig-" + newConfig.configNum(),
            newConfig.configNum(), new NewConfigCmd(newConfig)), paxosAddress);
      }
    }
  }

  private void handlePaxosDecision(PaxosDecision m, Address sender) {
    Command cmd = m.command();
    if (cmd instanceof ShardStoreCommand) {
      processKVRequest(((ShardStoreCommand) cmd).request());
    } else if (cmd instanceof NewConfigCmd) {
      processNewConfig(((NewConfigCmd) cmd).config());
    } else if (cmd instanceof ShardMoveCmd) {
      processMoveRequest(((ShardMoveCmd) cmd).request());
    } else if (cmd instanceof ShardMoveAckCmd) {
      processMoveReply(((ShardMoveAckCmd) cmd).reply());
    } else if (cmd instanceof TxnClientReqCmd) {
      processShardStoreCoordinator(((TxnClientReqCmd) cmd).request());
    } else if (cmd instanceof TxnPrepareReqCmd) {
      processPrepareTransactionRequest(((TxnPrepareReqCmd) cmd).request());
    } else if (cmd instanceof TxnPrepareReplyCmd) {
      processPrepareTransactionReply(((TxnPrepareReplyCmd) cmd).reply());
    } else if (cmd instanceof TxnCommitReqCmd) {
      processCommitTransactionRequest(((TxnCommitReqCmd) cmd).request());
    } else if (cmd instanceof TxnCommitReplyCmd) {
      processCommitTransactionReply(((TxnCommitReplyCmd) cmd).reply());
    }
  }

  /*
   * -----------------------------------------------------------------------------
   * Timer Handlers
   * -----------------------------------------------------------------------------
   */
  void onPingTimer(PingTimer t) {
    if (isStable()) {
      sendConfigRequest(currentConfigNum + 1);
    }
    set(t, PING_RETRY_MILLIS);
  }

  void onMoveTimer(MoveTimer t) {
    if (t.request().configNum() == currentConfigNum
        && currentManagedShards.contains(t.request().shardId())) {
      broadcast(t.request(), t.destination());
      set(t, MOVE_RETRY_MILLIS);
    }
  }

  void onPrepareTimer(PrepareTimer t) {
    // Only retry if we're still trying to PREPARE this exact transaction and
    // the destination group hasn't already voted.
    if (txnPhase != TxnPhase.PREPARING
        || currentTxn == null
        || !currentTxn.equals(t.request().command())) {
      return;
    }
    // Find the destination group to know if we already heard YES from it.
    Integer destGroup = groupForServers(t.destination());
    if (destGroup == null || txnPrepareYes.contains(destGroup)) return;
    broadcast(t.request(), t.destination());
    set(t, PREPARE_RETRY_MILLIS);
  }

  void onCommitTimer(CommitTimer t) {
    // Retry COMMIT/ABORT to a participant we haven't heard back from.
    if (currentTxn == null || !currentTxn.equals(t.request().command())) return;
    if (txnPhase != TxnPhase.COMMITTING && txnPhase != TxnPhase.ABORTING) return;
    Integer destGroup = groupForServers(t.destination());
    if (destGroup == null || txnAcks.contains(destGroup)) return;
    broadcast(t.request(), t.destination());
    set(t, COMMIT_RETRY_MILLIS);
  }

  /*
   * -----------------------------------------------------------------------------
   * Transaction helpers
   * -----------------------------------------------------------------------------
   */

  private boolean tryLock(AMOCommand cmd) {
    if (lockHolder == null) {
      lockHolder = cmd;
      return true;
    }
    return lockHolder.equals(cmd);
  }

  private void releaseLock(AMOCommand cmd) {
    if (cmd.equals(lockHolder)) {
      lockHolder = null;
    }
  }

  private Set<Integer> participantsForTransaction(Transaction txn) {
    Set<Integer> participants = new HashSet<>();
    for (String key : txn.keySet()) {
      int shardId = keyToShard(key);
      for (var entry : currentConfig.entrySet()) {
        if (entry.getValue().getRight().contains(shardId)) {
          participants.add(entry.getKey());
          break;
        }
      }
    }
    return participants;
  }

  // Run the transaction over keys whose shard we currently own.  Used by:
  //   - the coordinator's single-group fast path (we own all keys), and
  //   - both coordinator and participants in multi-group commit (each runs
  //     on its own subset; the coordinator merges).
  private KVStoreResult executeTransactionOnOwnedShards(Transaction txn) {
    Set<String> ourKeys = new HashSet<>();
    for (String key : txn.keySet()) {
      if (currentManagedShards.contains(keyToShard(key))) {
        ourKeys.add(key);
      }
    }

    Map<String, String> db = new HashMap<>();
    for (String key : ourKeys) {
      Map<String, String> shardStore = shardStoreFor(keyToShard(key));
      if (shardStore.containsKey(key)) {
        db.put(key, shardStore.get(key));
      }
    }

    KVStoreResult result = txn.run(db);

    for (String key : txn.writeSet()) {
      if (!ourKeys.contains(key)) continue;
      Map<String, String> shardStore = shardStoreFor(keyToShard(key));
      if (db.containsKey(key)) {
        shardStore.put(key, db.get(key));
      } else {
        shardStore.remove(key);
      }
    }
    return result;
  }

  private Map<String, String> shardStoreFor(int shardId) {
    TransactionalKVStore tkvs =
        (TransactionalKVStore) app.get(shardId).application();
    return tkvs.store();
  }

  // Combine partial results from each participant into a final result.
  private KVStoreResult mergePartials(Transaction txn, Map<Integer, KVStoreResult> partials) {
    if (txn instanceof MultiGet) {
      Map<String, String> merged = new HashMap<>();
      for (String key : txn.keySet()) {
        merged.put(key, MultiGetResult.KEY_NOT_FOUND);
      }
      for (KVStoreResult r : partials.values()) {
        if (!(r instanceof MultiGetResult)) continue;
        for (var e : ((MultiGetResult) r).values().entrySet()) {
          // Only overwrite if the partial actually has a real value — a
          // participant returns KEY_NOT_FOUND for keys outside its shards.
          if (!MultiGetResult.KEY_NOT_FOUND.equals(e.getValue())) {
            merged.put(e.getKey(), e.getValue());
          }
        }
      }
      return new MultiGetResult(merged);
    }
    if (txn instanceof MultiPut) return new MultiPutOk();
    if (txn instanceof Swap) return new SwapOk();
    throw new IllegalStateException("Unknown transaction type: " + txn);
  }

  // For a destination address array (one group's servers), determine the
  // groupId.  Used by the timer handlers to decide whether to retry.
  private Integer groupForServers(Address[] servers) {
    if (currentConfig == null || servers == null || servers.length == 0) return null;
    Address probe = servers[0];
    for (var entry : currentConfig.entrySet()) {
      if (entry.getValue().getLeft().contains(probe)) {
        return entry.getKey();
      }
    }
    return null;
  }

  /*
   * -----------------------------------------------------------------------------
   * Utils (unchanged)
   * -----------------------------------------------------------------------------
   */
  void sendConfigRequest(Integer configNum) {
    broadcastToShardMasters(new PaxosRequest(PAXOS_PING_ID, 0, new Query(configNum)));
  }

  private void handleMove() {
    Set<Integer> shardsToMove = new HashSet<>(currentManagedShards);
    if (currentConfig.containsKey(groupId)) {
      shardsToMove.removeAll(currentConfig.get(groupId).getRight());
    }

    for (Integer shardId : shardsToMove) {
      for (var entry : currentConfig.entrySet()) {
        Pair<Set<Address>, Set<Integer>> value = entry.getValue();
        AMOApplication moveApp = app.get(shardId);
        if (value.getRight().contains(shardId)) {
          MoveRequest request = new MoveRequest(currentConfigNum, shardId, moveApp, group);
          Address[] dest = value.getLeft().toArray(new Address[0]);
          broadcast(request, dest);
          set(new MoveTimer(dest, request), MOVE_RETRY_MILLIS);
        }
      }
    }
  }

  private boolean isStable() {
    if (currentConfigNum == -1) return true;
    if (!currentConfig.containsKey(groupId)) {
      return currentManagedShards.isEmpty();
    }
    return currentManagedShards.equals(currentConfig.get(groupId).getRight());
  }

  private void processKVRequest(ShardStoreRequest m) {
    AMOCommand command = (AMOCommand) m.command();
    if (m.configNum() != currentConfigNum) {
      send(new ShardStoreReply(currentConfigNum, null), command.address());
      if (m.configNum() > currentConfigNum) {
        sendConfigRequest(currentConfigNum + 1);
      }
      return;
    }
    if (!currentConfig.containsKey(groupId)) {
      send(new ShardStoreReply(currentConfigNum, null), command.address());
      return;
    }
    Integer shardId = keyToShard(m.key());
    if (!currentConfig.get(groupId).getRight().contains(shardId)
        || !currentManagedShards.contains(shardId)) {
      send(new ShardStoreReply(currentConfigNum, null), command.address());
      return;
    }
    AMOResult result = app.get(shardId).execute(m.command());
    send(new ShardStoreReply(currentConfigNum, result), command.address());
  }

  private void processMoveRequest(MoveRequest m) {
    if (m.configNum() > currentConfigNum) {
      sendConfigRequest(currentConfigNum + 1);
      return;
    }
    if (m.configNum() < currentConfigNum) {
      broadcast(new MoveReply(m.configNum(), m.shardId()), m.senders());
      return;
    }
    if (currentConfig.containsKey(groupId) && currentConfig.get(groupId).getRight().contains(m.shardId())) {
      if (!currentManagedShards.contains(m.shardId())) {
        app.put(m.shardId(), m.app());
        currentManagedShards.add(m.shardId());
      }
      broadcast(new MoveReply(currentConfigNum, m.shardId()), m.senders());
    }
  }

  private void processMoveReply(MoveReply m) {
    if (m.configNum() > currentConfigNum) {
      sendConfigRequest(currentConfigNum + 1);
    }
    if (m.configNum() != currentConfigNum) return;
    if (currentManagedShards.contains(m.shardId())) {
      currentManagedShards.remove(m.shardId());
      app.remove(m.shardId());
    }
  }

  void processNewConfig(ShardConfig newConfig) {
    if (newConfig.configNum() != currentConfigNum + 1) return;
    currentConfigNum = newConfig.configNum();
    currentConfig = newConfig.groupInfo();

    if (currentConfigNum == 0) {
      if (currentConfig.containsKey(groupId)) {
        currentManagedShards.addAll(currentConfig.get(groupId).getRight());
        for (Integer shard : currentManagedShards) {
          app.putIfAbsent(shard, new AMOApplication<>(new TransactionalKVStore()));
        }
      }
    } else {
      handleMove();
    }
  }
}
