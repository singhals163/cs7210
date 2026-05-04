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
import static dslabs.shardkv.ConfigProposeTimer.CONFIG_PROPOSE_DELAY_MILLIS;

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

  // Per-key locks.  Each transaction acquires (atomically, all-or-nothing)
  // the locks for the keys it touches at this group's shards.  Disjoint
  // transactions can therefore run concurrently — fixes the throughput
  // bottleneck where unrelated transactions used to serialize on a single
  // server-wide lock.  Re-entrant: keyLocks.get(k) == cmd is OK for cmd.
  private Map<String, AMOCommand> keyLocks = new HashMap<>();

  // Coordinator-side AMO cache: (clientAddr) -> last-seen finished result.
  // Used to short-circuit retries from the client of an already-completed txn.
  private Map<Address, AMOResult> txnAmoCache = new HashMap<>();

  // Participant-side cache: (clientAddr) -> last partial result we returned
  // for a COMMIT, so retried COMMITs are idempotent (don't re-execute against
  // our store).
  private Map<Address, AMOResult> participantTxnCache = new HashMap<>();

  // Per-transaction coordinator state.  Indexed by the AMOCommand that
  // identifies the transaction, so multiple transactions can be coordinated
  // concurrently as long as their per-key locks don't conflict.
  private enum TxnPhase { PREPARING, COMMITTING, ABORTING }

  private static class CoordState {
    Set<Integer> participants;
    TxnPhase phase = TxnPhase.PREPARING;
    Set<Integer> prepareYes = new HashSet<>();
    Set<Integer> acks = new HashSet<>();
    Map<Integer, KVStoreResult> partials = new HashMap<>();
    // Aggregated readSet values from every participant who voted YES.  Used
    // in beginCommitPhase to build a full pre-image db that includes values
    // from shards we don't own (needed for Swap-style cross-shard writes).
    Map<String, String> readValues = new HashMap<>();
  }

  private Map<AMOCommand, CoordState> activeTxns = new HashMap<>();

  // Deferred reconfiguration: when a new ShardConfig arrives but we still have
  // locks held / transactions in flight, we can't safely apply it yet (would
  // either lose writes from in-flight commits or diverge replicas if some
  // applied at different times).  Stash here and re-check after every lock
  // release / txn completion.  Stays set until processNewConfig actually
  // applies, so the pre-paxos-decide window also blocks new client work.
  private boolean pendingConfigChange = false;
  private ShardConfig pendingConfig = null;

  // Monotonic counter used as a "retry attempt" suffix on internal paxos
  // proposal ids (newConfig-*, shardMove-*, shardMoveAck-*).  Without it,
  // a second propose of the same logical request (e.g. a PingTimer firing
  // newConfig-4 again because the previous decide's processNewConfig hit
  // a post-paxos pre-check and early-returned) carries the same (id,
  // sequenceNum) — paxos treats it as a duplicate and never delivers
  // another PaxosDecision.  Bumping the counter per propose guarantees
  // a fresh paxos slot for every retry.
  private int paxosProposalAttempt = 0;

  // True iff a ConfigProposeTimer is already armed.  Prevents queueing
  // multiple timers (and thus multiple paxos slots) for the same pending
  // config change when several lock-release paths fire in quick succession.
  private boolean configProposeTimerArmed = false;

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
    Transaction txn = (Transaction) command.command();

    // 1. Already-completed retries: serve from AMO cache, no paxos needed.
    AMOResult cached = txnAmoCache.get(command.address());
    if (cached != null && cached.sequenceNumber() >= command.sequenceNumber()) {
      send(new ShardStoreReply(currentConfigNum, cached), command.address());
      return;
    }

    // 2. Already coordinating exactly this txn: timers are driving it.
    if (activeTxns.containsKey(command)) {
      return;
    }

    // 3. A config change is waiting to apply — don't admit any new client
    //    transactions, so existing ones can drain and the deferred change
    //    can fire.  Client retries; once we're past the change it'll succeed
    //    against the new config.
    if (pendingConfigChange) {
      send(new ShardStoreReply(currentConfigNum, null), command.address());
      return;
    }

    // 4. Per-key conflict: any of our keys held by a *different* txn.  Reject
    //    directly so we don't burn a paxos slot we know will fail.
    Set<String> myKeys = ourKeysFor(txn);
    if (!canLockKeys(command, myKeys)) {
      send(new ShardStoreReply(currentConfigNum, null), command.address());
      return;
    }

    // 4. Standard config / membership / shard-ownership pre-checks.
    if (!coordinatorPreChecksPass(m, command, txn)) return;

    // 5. Propose. attempt makes each client retry a fresh paxos slot so a
    //    post-paxos rejection on a previous attempt doesn't dedup-drop us.
    String id = "txnClient-" + command.address() + "-" + currentConfigNum
        + "-" + m.attempt();
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

    // Already coordinating this txn — let in-flight timers drive it.
    if (activeTxns.containsKey(command)) {
      return;
    }

    // Acquire per-key locks for the keys we own.  If any conflict with another
    // active txn, reject; client will retry with attempt+1 and a fresh slot.
    Set<String> myKeys = ourKeysFor(txn);
    if (!tryLockKeys(command, myKeys)) {
      send(new ShardStoreReply(currentConfigNum, null), command.address());
      return;
    }

    Set<Integer> participants = participantsForTransaction(txn);

    // Single-group fast path: skip 2PC.  We own every touched key, so no
    // external read values are needed.
    if (participants.size() == 1) {
      KVStoreResult result = executeTransactionOnOwnedShards(txn, null);
      AMOResult amoResult = new AMOResult(command.sequenceNumber(), result);
      txnAmoCache.put(command.address(), amoResult);
      releaseLocks(command);
      send(new ShardStoreReply(currentConfigNum, amoResult), command.address());
      maybeTriggerPendingConfigChange();
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

  // Phase 1: register the txn in activeTxns and send PREPARE to every other
  // participant; we count ourselves as already-prepared because we already
  // hold the per-key locks from processShardStoreCoordinator.
  private void beginPreparePhase(AMOCommand command, Set<Integer> participants) {
    CoordState st = new CoordState();
    st.participants = participants;
    st.prepareYes.add(groupId);
    activeTxns.put(command, st);

    for (Integer other : participants) {
      if (other == groupId) continue;
      Address[] dest = currentConfig.get(other).getLeft().toArray(new Address[0]);
      PrepareTransactionRequest req =
          new PrepareTransactionRequest(currentConfigNum, command, group, 0);
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
    CoordState st = activeTxns.get(m.command());
    if (st == null || st.phase != TxnPhase.PREPARING) return;

    // We do *not* gate this on m.configNum() == currentConfigNum.  A
    // participant that voted NO might already have advanced to a newer config
    // (it didn't acquire any lock for this txn, so its `canApply` became true
    // and it applied a deferred config change).  Its NO would carry the new
    // configNum.  We still must process it — otherwise coord stays in
    // PREPARING forever, T_a never aborts, the coord's pending config change
    // never fires, and the client hangs indefinitely.  The activeTxns lookup
    // above (txn identity) is the correct staleness filter.
    if (m.result()) {
      st.prepareYes.add(m.groupId());
      // Accumulate this participant's slice of the pre-image db; we'll need
      // every YES voter's readSet values when running cross-shard txns.
      if (m.readValues() != null) {
        st.readValues.putAll(m.readValues());
      }
      if (st.prepareYes.equals(st.participants)) {
        beginCommitPhase(m.command());
      }
    } else {
      beginAbortPhase(m.command());
    }
  }

  // Phase 2 (commit): execute locally now (we have all YES votes), then send
  // COMMIT to other participants.  Coordinator's own partial result is saved
  // immediately so the merge in finishCommit always has it.
  private void beginCommitPhase(AMOCommand command) {
    CoordState st = activeTxns.get(command);
    if (st == null) return;

    Transaction txn = (Transaction) command.command();

    // Coordinator never sent itself a PREPARE, so its readSet values aren't
    // in st.readValues yet.  Add them now to form the full pre-image db
    // that gets shipped to every participant in COMMIT.
    Map<String, String> fullReadValues = new HashMap<>(st.readValues);
    fullReadValues.putAll(readLocalReadSetValues(txn));

    KVStoreResult ourPartial = executeTransactionOnOwnedShards(txn, fullReadValues);
    st.partials.put(groupId, ourPartial);

    st.phase = TxnPhase.COMMITTING;
    st.acks.add(groupId);

    for (Integer other : st.participants) {
      if (other == groupId) continue;
      Address[] dest = currentConfig.get(other).getLeft().toArray(new Address[0]);
      CommitTransactionRequest req =
          new CommitTransactionRequest(currentConfigNum, command, group, true, 0,
              fullReadValues);
      broadcast(req, dest);
      set(new CommitTimer(dest, req), COMMIT_RETRY_MILLIS);
    }

    if (st.acks.equals(st.participants)) finishCommit(command);
  }

  // Phase 2 (abort): at least one NO vote came in.  Send ABORT to every
  // participant (not just the YES voters) — a slow PREPARE could arrive at a
  // participant after we decided to abort, and we want that participant's
  // eventual lock to get released.  ABORT is idempotent at the participant.
  // Reply null to the client immediately so it can retry.
  private void beginAbortPhase(AMOCommand command) {
    CoordState st = activeTxns.get(command);
    if (st == null) return;

    st.phase = TxnPhase.ABORTING;
    st.acks.clear();
    st.acks.add(groupId);

    for (Integer other : st.participants) {
      if (other == groupId) continue;
      Address[] dest = currentConfig.get(other).getLeft().toArray(new Address[0]);
      // ABORT carries no read values — participant just releases locks.
      CommitTransactionRequest req =
          new CommitTransactionRequest(currentConfigNum, command, group, false, 0, null);
      broadcast(req, dest);
      set(new CommitTimer(dest, req), COMMIT_RETRY_MILLIS);
    }

    send(new ShardStoreReply(currentConfigNum, null), command.address());

    if (st.acks.equals(st.participants)) {
      finishAbort(command);
    }
  }

  private void handleCommitTransactionReply(CommitTransactionReply m, Address sender) {
    String id = "txnCommitReply-" + m.command().address() + "-" + m.command().sequenceNumber()
        + "-" + m.groupId() + "-" + currentConfigNum;
    handleMessage(new PaxosRequest(id, currentConfigNum,
        new TxnCommitReplyCmd(m)), paxosAddress);
  }

  private void processCommitTransactionReply(CommitTransactionReply m) {
    CoordState st = activeTxns.get(m.command());
    if (st == null) return;                                // stale or unknown
    if (st.phase != TxnPhase.COMMITTING && st.phase != TxnPhase.ABORTING) return;

    // No configNum gate here either: in ABORTING we may receive acks from
    // participants who advanced to a newer config (they had no lock for this
    // txn and hit `canApply==true`).  Dropping their acks would strand coord
    // forever waiting on participants whose mailboxes are already empty.
    // For COMMITTING, participants have locks that prevent them from
    // advancing while we're in flight, so configNums *should* match
    // naturally — but we don't enforce it here either; activeTxns identity
    // is the staleness filter.
    st.acks.add(m.groupId());
    if (st.phase == TxnPhase.COMMITTING && m.partialResult() != null) {
      st.partials.put(m.groupId(), m.partialResult());
    }

    if (st.acks.equals(st.participants)) {
      if (st.phase == TxnPhase.COMMITTING) finishCommit(m.command());
      else finishAbort(m.command());
    }
  }

  private void finishCommit(AMOCommand command) {
    CoordState st = activeTxns.get(command);
    if (st == null) return;
    Transaction txn = (Transaction) command.command();
    KVStoreResult finalResult = mergePartials(txn, st.partials);
    AMOResult amoResult = new AMOResult(command.sequenceNumber(), finalResult);

    txnAmoCache.put(command.address(), amoResult);
    activeTxns.remove(command);
    releaseLocks(command);
    send(new ShardStoreReply(currentConfigNum, amoResult), command.address());
    maybeTriggerPendingConfigChange();
  }

  private void finishAbort(AMOCommand command) {
    activeTxns.remove(command);
    releaseLocks(command);
    // Null reply was already sent at the start of beginAbortPhase.
    maybeTriggerPendingConfigChange();
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
    Transaction txn = (Transaction) command.command();

    // 1. Already-committed txn: reply YES from cache, no paxos needed.
    AMOResult cached = participantTxnCache.get(command.address());
    if (cached != null && cached.sequenceNumber() >= command.sequenceNumber()) {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, true, groupId, readLocalReadSetValues(txn)), m.senders());
      return;
    }

    Set<String> myKeys = ourKeysFor(txn);

    // 2. Already prepared for this exact txn (we hold all our keys for it):
    //    re-vote YES, idempotent.  This *must* be checked before the pending-
    //    config-change check below, otherwise a transaction that we voted YES
    //    on before the config change arrived would get a NO on its retry and
    //    abort unnecessarily.
    if (weHoldKeys(command, myKeys)) {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, true, groupId, readLocalReadSetValues(txn)), m.senders());
      return;
    }

    // 3. A config change is waiting — don't accept any new PREPAREs.  Coord
    //    will see NO and abort; client retries; on the new config the txn
    //    runs cleanly.
    if (pendingConfigChange) {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, false, groupId, null), m.senders());
      return;
    }

    // 4. Per-key conflict with another txn: vote NO directly.
    if (!canLockKeys(command, myKeys)) {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, false, groupId, null), m.senders());
      return;
    }

    // 4. Standard config / membership / shard-ownership pre-checks.
    if (m.configNum() != currentConfigNum) {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, false, groupId, null), m.senders());
      if (m.configNum() > currentConfigNum) {
        sendConfigRequest(currentConfigNum + 1);
      }
      return;
    }
    if (currentConfig == null || !currentConfig.containsKey(groupId) || !isStable()) {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, false, groupId, null), m.senders());
      return;
    }
    if (myKeys.isEmpty()) {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, false, groupId, null), m.senders());
      return;
    }

    // 5. Propose.  attempt makes each coordinator retry a fresh paxos slot.
    String id = "txnPrep-" + command.address() + "-" + command.sequenceNumber()
        + "-" + currentConfigNum + "-" + m.attempt();
    handleMessage(new PaxosRequest(id, currentConfigNum,
        new TxnPrepareReqCmd(m)), paxosAddress);
  }

  private void processPrepareTransactionRequest(PrepareTransactionRequest m) {
    AMOCommand command = m.command();
    Transaction txn = (Transaction) command.command();

    if (m.configNum() != currentConfigNum) {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, false, groupId, null), m.senders());
      if (m.configNum() > currentConfigNum) {
        sendConfigRequest(currentConfigNum + 1);
      }
      return;
    }
    if (currentConfig == null || !currentConfig.containsKey(groupId) || !isStable()) {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, false, groupId, null), m.senders());
      return;
    }

    Set<String> myKeys = ourKeysFor(txn);
    if (myKeys.isEmpty()) {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, false, groupId, null), m.senders());
      return;
    }

    // Already-committed txn: re-vote YES so coordinator's COMMIT retry hits
    // our cache.
    AMOResult cached = participantTxnCache.get(command.address());
    if (cached != null && cached.sequenceNumber() >= command.sequenceNumber()) {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, true, groupId, readLocalReadSetValues(txn)), m.senders());
      return;
    }

    if (tryLockKeys(command, myKeys)) {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, true, groupId, readLocalReadSetValues(txn)), m.senders());
    } else {
      broadcast(new PrepareTransactionReply(currentConfigNum, command, false, groupId, null), m.senders());
    }
  }

  // Thin wrapper: route the COMMIT/ABORT through paxos so all replicas in the
  // participant group apply the transaction's writes (or release the lock) at
  // the same paxos slot.
  private void handleCommitTransactionRequest(CommitTransactionRequest m, Address sender) {
    AMOCommand command = m.command();

    // Already-committed COMMIT: reply with cached partial regardless of
    // configNum.  This MUST come before the configNum check — once we've
    // executed and cached a partial result, a retry that arrives after we've
    // advanced to a newer config still owes the coordinator the *original*
    // partial.  Replying with `partial=null` here would silently drop the
    // participant's contribution from `mergePartials` and produce KeyNotFound
    // values for keys that were actually written.
    if (m.commit()) {
      AMOResult cached = participantTxnCache.get(command.address());
      if (cached != null && cached.sequenceNumber() >= command.sequenceNumber()) {
        broadcast(new CommitTransactionReply(
            currentConfigNum, command, true, groupId,
            (KVStoreResult) cached.result()), m.senders());
        return;
      }
    }

    // Stale config and we don't hold a lock for this txn: ack directly so the
    // coordinator can drop us; we don't burn a paxos slot on a no-op.  But
    // *if* we still hold a lock for this command (acquired at PREPARE under
    // a previous config and never released), we MUST go through paxos so
    // processCommitTransactionRequest can release it on every replica —
    // otherwise the lock pins canApplyConfigChange=false forever and the
    // server wedges on its current config.  Single-server paxos reproduces
    // this as the test-4.7 deadlock; multi-server replicas would diverge if
    // we released directly here.
    if (m.configNum() != currentConfigNum) {
      if (!holdsLockFor(command)) {
        broadcast(new CommitTransactionReply(
            currentConfigNum, command, m.commit(), groupId, null), m.senders());
        return;
      }
      // fall through to paxos so the lock release is replicated.
    }

    String id = "txnCommit-" + command.address() + "-" + command.sequenceNumber()
        + "-" + currentConfigNum + "-" + (m.commit() ? "C" : "A")
        + "-" + m.attempt();
    handleMessage(new PaxosRequest(id, currentConfigNum,
        new TxnCommitReqCmd(m)), paxosAddress);
  }

  private void processCommitTransactionRequest(CommitTransactionRequest m) {
    AMOCommand command = m.command();
    Transaction txn = (Transaction) command.command();

    // Cache check FIRST — same reasoning as in handleCommitTransactionRequest.
    // A retried COMMIT for an already-committed txn MUST return the cached
    // partial, otherwise the coord's mergePartials produces KeyNotFound.
    if (m.commit()) {
      AMOResult cached = participantTxnCache.get(command.address());
      if (cached != null && cached.sequenceNumber() >= command.sequenceNumber()) {
        broadcast(new CommitTransactionReply(
            currentConfigNum, command, true, groupId,
            (KVStoreResult) cached.result()), m.senders());
        return;
      }
    }

    if (m.configNum() != currentConfigNum) {
      // Release any lock we still hold for this command (defensive; should
      // be rare since canApplyConfigChange normally pins us at the txn's
      // config).  Do NOT execute the txn — at the new config our shard
      // ownership may differ; coord's merge tolerates a null partial.
      releaseLocks(command);
      broadcast(new CommitTransactionReply(
          currentConfigNum, command, m.commit(), groupId, null), m.senders());
      maybeTriggerPendingConfigChange();
      return;
    }

    if (m.commit()) {
      Set<String> myKeys = ourKeysFor(txn);
      if (weHoldKeys(command, myKeys)) {
        // Use the coord's aggregated readValues so cross-shard writes (Swap)
        // see values from shards we don't own.
        KVStoreResult partial = executeTransactionOnOwnedShards(txn, m.readValues());
        AMOResult amoResult = new AMOResult(command.sequenceNumber(), partial);
        participantTxnCache.put(command.address(), amoResult);
        releaseLocks(command);
        broadcast(new CommitTransactionReply(
            currentConfigNum, command, true, groupId, partial), m.senders());
        maybeTriggerPendingConfigChange();
        return;
      }
      // Defensive: not in cache, don't hold the per-key locks — just ack with
      // no result; coordinator's merge tolerates absence of our partial.
      broadcast(new CommitTransactionReply(
          currentConfigNum, command, true, groupId, null), m.senders());
    } else {
      releaseLocks(command);
      broadcast(new CommitTransactionReply(
          currentConfigNum, command, false, groupId, null), m.senders());
      maybeTriggerPendingConfigChange();
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
        // Fresh attempt suffix per propose so a previously-deduped paxos slot
        // (whose decide hit a post-paxos pre-check and early-returned) doesn't
        // permanently swallow this shardMove request.
        String id = "shardMove-" + m.shardId() + "-" + m.configNum()
            + "-" + (++paxosProposalAttempt);
        handleMessage(new PaxosRequest(id, m.configNum(),
            new ShardMoveCmd(m)), paxosAddress);
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
      String id = "shardMoveAck-" + m.shardId() + "-" + m.configNum()
          + "-" + (++paxosProposalAttempt);
      handleMessage(new PaxosRequest(id, m.configNum(),
          new ShardMoveAckCmd(m)), paxosAddress);
    }
  }

  void handlePaxosReply(PaxosReply m, Address sender) {
    if (!PAXOS_PING_ID.equals(m.id())) return;
    if (m.result() instanceof ShardConfig) {
      ShardConfig newConfig = (ShardConfig) m.result();
      if (newConfig.configNum() == currentConfigNum + 1 && isStable()) {
        if (canApplyConfigChange()) {
          // Idle: propose immediately.  Fresh attempt suffix in case an earlier
          // newConfig-N proposal got deduped at paxos but its decide hit a
          // post-paxos pre-check and early-returned without advancing.
          String id = "newConfig-" + newConfig.configNum()
              + "-" + (++paxosProposalAttempt);
          handleMessage(new PaxosRequest(id, newConfig.configNum(),
              new NewConfigCmd(newConfig)), paxosAddress);
        } else {
          // In-flight transaction(s) — defer.  maybeTriggerPendingConfigChange
          // will propose once those finish and release their locks.
          pendingConfigChange = true;
          pendingConfig = newConfig;
        }
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
    AMOCommand command = t.request().command();
    CoordState st = activeTxns.get(command);
    if (st == null || st.phase != TxnPhase.PREPARING) return;
    Integer destGroup = groupForServers(t.destination());
    if (destGroup == null || st.prepareYes.contains(destGroup)) return;

    // Fresh request with incremented attempt so the participant's paxos id is
    // unique per retry — any post-paxos rejection on the previous attempt
    // (transient lock conflict, etc.) doesn't dedup-drop the retry.
    PrepareTransactionRequest old = t.request();
    PrepareTransactionRequest next = new PrepareTransactionRequest(
        old.configNum(), old.command(), old.senders(), old.attempt() + 1);
    broadcast(next, t.destination());
    set(new PrepareTimer(t.destination(), next), PREPARE_RETRY_MILLIS);
  }

  void onCommitTimer(CommitTimer t) {
    AMOCommand command = t.request().command();
    CoordState st = activeTxns.get(command);
    if (st == null) return;
    if (st.phase != TxnPhase.COMMITTING && st.phase != TxnPhase.ABORTING) return;
    Integer destGroup = groupForServers(t.destination());
    if (destGroup == null || st.acks.contains(destGroup)) return;

    CommitTransactionRequest old = t.request();
    // Forward the same readValues — they're computed once at the start of
    // beginCommitPhase and don't change across retries.
    CommitTransactionRequest next = new CommitTransactionRequest(
        old.configNum(), old.command(), old.senders(), old.commit(),
        old.attempt() + 1, old.readValues());
    broadcast(next, t.destination());
    set(new CommitTimer(t.destination(), next), COMMIT_RETRY_MILLIS);
  }

  /*
   * -----------------------------------------------------------------------------
   * Transaction helpers
   * -----------------------------------------------------------------------------
   */

  // ----- Per-key lock helpers -----

  // Subset of the txn's keys that map to shards we currently own.
  private Set<String> ourKeysFor(Transaction txn) {
    Set<String> result = new HashSet<>();
    for (String key : txn.keySet()) {
      if (currentManagedShards.contains(keyToShard(key))) {
        result.add(key);
      }
    }
    return result;
  }

  // True iff every key in `keys` is either free or already held by `cmd`.
  private boolean canLockKeys(AMOCommand cmd, Set<String> keys) {
    for (String key : keys) {
      AMOCommand holder = keyLocks.get(key);
      if (holder != null && !holder.equals(cmd)) return false;
    }
    return true;
  }

  // Atomic all-or-nothing acquire.  Returns true iff the lock was taken (or
  // already held by `cmd` — re-entrant).
  private boolean tryLockKeys(AMOCommand cmd, Set<String> keys) {
    if (!canLockKeys(cmd, keys)) return false;
    for (String key : keys) {
      keyLocks.put(key, cmd);
    }
    return true;
  }

  // True iff every key in `keys` is currently held by `cmd`.  Used to detect
  // "we already prepared this exact txn — re-vote YES idempotently."
  private boolean weHoldKeys(AMOCommand cmd, Set<String> keys) {
    if (keys.isEmpty()) return false;
    for (String key : keys) {
      if (!cmd.equals(keyLocks.get(key))) return false;
    }
    return true;
  }

  private void releaseLocks(AMOCommand cmd) {
    keyLocks.entrySet().removeIf(e -> cmd.equals(e.getValue()));
  }

  // True iff at least one key in keyLocks is held by cmd.  Used pre-paxos in
  // handleCommitTransactionRequest to decide whether a configNum-mismatched
  // COMMIT/ABORT can be acked directly (no lock to release) or must go
  // through paxos so processCommitTransactionRequest can release on every
  // replica.
  private boolean holdsLockFor(AMOCommand cmd) {
    for (AMOCommand holder : keyLocks.values()) {
      if (cmd.equals(holder)) return true;
    }
    return false;
  }

  // ----- Deferred-reconfiguration helpers -----

  // Safe to apply a config change right now iff there are no in-flight txns
  // (no coordinator state, no per-key locks held).
  private boolean canApplyConfigChange() {
    return keyLocks.isEmpty() && activeTxns.isEmpty();
  }

  // Called after every lock release / activeTxns removal — if a config change
  // was deferred and we're now idle, fire the proposal.
  // Arms a ConfigProposeTimer to fire on the next event-loop iteration; the
  // timer handler does the actual paxos propose.  We deliberately do *not*
  // call handleMessage(PaxosRequest, paxosAddress) synchronously here:
  // maybeTriggerPendingConfigChange is invoked from inside paxos-decide
  // handlers (processCommitTransactionRequest, finishCommit, finishAbort,
  // single-group fast path), and the local PaxosServer's drain loop bumps
  // its slotOut *after* sendRequestReply, so a synchronous re-propose
  // re-enters the same drain at the same slotOut and recurses without
  // bound (see test 4.7 stack overflow).
  private void maybeTriggerPendingConfigChange() {
    if (pendingConfigChange && pendingConfig != null && canApplyConfigChange()
        && !configProposeTimerArmed) {
      configProposeTimerArmed = true;
      set(new ConfigProposeTimer(), CONFIG_PROPOSE_DELAY_MILLIS);
    }
  }

  void onConfigProposeTimer(ConfigProposeTimer t) {
    configProposeTimerArmed = false;
    // Re-check conditions; pendingConfigChange may have been cleared by
    // a different path, or canApply may have flipped back to false.
    if (pendingConfigChange && pendingConfig != null && canApplyConfigChange()) {
      ShardConfig cfg = pendingConfig;
      // NB: do NOT clear `pendingConfigChange` yet.  We keep blocking new
      // client work until processNewConfig actually applies; the flag is
      // cleared there.
      String id = "newConfig-" + cfg.configNum() + "-" + (++paxosProposalAttempt);
      handleMessage(new PaxosRequest(id, cfg.configNum(),
          new NewConfigCmd(cfg)), paxosAddress);
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
  //   - the coordinator's single-group fast path (we own all keys, pass null
  //     for externalReadValues), and
  //   - both coordinator and participants in multi-group commit, where the
  //     coordinator first aggregates readSet values from every participant
  //     and ships them via `externalReadValues` so cross-shard writes (Swap)
  //     can compute correctly.  Each participant still only writes back to
  //     its own keys.
  private KVStoreResult executeTransactionOnOwnedShards(
      Transaction txn, Map<String, String> externalReadValues) {
    Set<String> ourKeys = new HashSet<>();
    for (String key : txn.keySet()) {
      if (currentManagedShards.contains(keyToShard(key))) {
        ourKeys.add(key);
      }
    }

    // Build a full pre-image db: start with externally-supplied values from
    // other participants' shards, then overlay our own current values (so
    // local state is authoritative for keys we own).
    Map<String, String> db = new HashMap<>();
    if (externalReadValues != null) {
      db.putAll(externalReadValues);
    }
    for (String key : ourKeys) {
      Map<String, String> shardStore = shardStoreFor(keyToShard(key));
      if (shardStore.containsKey(key)) {
        db.put(key, shardStore.get(key));
      } else {
        db.remove(key);
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

  // Read this server's local values for the txn's readSet keys that map to
  // shards we currently own.  Used by participants to ship their slice of
  // the pre-image db to the coordinator on PREPARE.
  private Map<String, String> readLocalReadSetValues(Transaction txn) {
    Map<String, String> values = new HashMap<>();
    for (String key : txn.readSet()) {
      int shardId = keyToShard(key);
      if (!currentManagedShards.contains(shardId)) continue;
      Map<String, String> shardStore = shardStoreFor(shardId);
      if (shardStore.containsKey(key)) {
        values.put(key, shardStore.get(key));
      }
    }
    return values;
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

    // Whatever we were deferring is now applied (or never matched anyway).
    pendingConfigChange = false;
    pendingConfig = null;

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
