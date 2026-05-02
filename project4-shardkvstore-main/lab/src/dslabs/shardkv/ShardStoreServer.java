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
import dslabs.kvstore.TransactionalKVStore.Transaction;

import org.apache.commons.lang3.tuple.Pair;

import static dslabs.shardkv.PingTimer.PING_RETRY_MILLIS;
import static dslabs.shardkv.MoveTimer.MOVE_RETRY_MILLIS;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class ShardStoreServer extends ShardStoreNode {
  private final Address[] group;
  private final int groupId;

  // ---------- paxos sub-node config (unchanged) ----------
  private static final String PAXOS_ADDRESS_ID = "paxos";
  private static final String PAXOS_PING_ID = "paxos-ping";
  private Address paxosAddress;

  // ---------- shard state ----------
  private Map<Integer, AMOApplication<Application>> app;
  private Map<Integer, Pair<Set<Address>, Set<Integer>>> currentConfig;
  private Integer currentConfigNum = -1;
  private Set<Integer> currentManagedShards;

  // ---------- transaction state ----------
  // Server-level lock with owner identity.  Held by the coordinator from the
  // moment we admit a transaction until we reply to the client; held by a
  // participant from the moment we vote YES on PREPARE until we receive the
  // matching COMMIT/ABORT.  Re-entrant for the same AMOCommand so duplicate
  // PREPAREs are idempotent.
  private AMOCommand lockHolder = null;

  // AMO cache for transactions: clientAddr -> last-seen result.
  // Single-key commands keep their existing per-shard AMOApplication cache;
  // transactions are cached at server level because they may span shards.
  private Map<Address, AMOResult> txnAmoCache = new HashMap<>();

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
   * Message Handlers
   * -----------------------------------------------------------------------------
   */

  // Top-level dispatch: route by command type.  SingleKey commands keep going
  // through paxos as before; transactions take the new coordinator path.
  private void handleShardStoreRequest(ShardStoreRequest m, Address sender) {
    Command inner = m.command().command();
    if (inner instanceof SingleKeyCommand) {
      handleShardStoreSingleKeyRequest(m, sender);
    } else if (inner instanceof Transaction) {
      handleShardStoreCoordinator(m, sender);
    }
  }

  // ---------------- SingleKey path (unchanged from part 3) ----------------
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

  // ---------------- Transaction coordinator path (step 2) ----------------
  // Step 2 handles only the single-group case end-to-end (lock → execute →
  // cache → reply).  Multi-group 2PC is left as a TODO for step 3; for now we
  // fall through to a `null` reply so the client retries / refreshes config.
  private void handleShardStoreCoordinator(ShardStoreRequest m, Address sender) {
    AMOCommand command = m.command();
    Transaction txn = (Transaction) command.command();

    // 1. Configuration / membership pre-checks.
    if (!coordinatorPreChecksPass(m, command, txn)) {
      return;
    }

    // 2. AMO cache: served retries don't re-execute.
    AMOResult cached = txnAmoCache.get(command.address());
    if (cached != null && cached.sequenceNumber() >= command.sequenceNumber()) {
      send(new ShardStoreReply(currentConfigNum, cached), command.address());
      return;
    }

    // 3. Acquire the server-level lock (owner-tracked).
    if (!tryLock(command)) {
      send(new ShardStoreReply(currentConfigNum, null), command.address());
      return;
    }

    Set<Integer> participants = participantsForTransaction(txn);

    // 4. Single-group fast path: just run it on our shards.  No 2PC, no paxos.
    if (participants.size() == 1) {
      KVStoreResult result = executeTransactionOnOwnedShards(txn);
      AMOResult amoResult = new AMOResult(command.sequenceNumber(), result);
      txnAmoCache.put(command.address(), amoResult);
      releaseLock(command);
      send(new ShardStoreReply(currentConfigNum, amoResult), command.address());
      return;
    }

    // 5. Multi-group case: deferred to step 3 (PREPARE → COMMIT 2PC).
    // For now release and reply null so the client doesn't hang.
    releaseLock(command);
    send(new ShardStoreReply(currentConfigNum, null), command.address());
  }

  // Group ownership of `keyToShard(key)` for every key in the transaction must
  // be settled (we own it AND have it).  Also enforces "client picks lowest
  // groupId as coordinator" server-side.
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

    // Every key whose shard is owned by us must actually be in
    // currentManagedShards (i.e. we've fully received it).
    for (String key : txn.keySet()) {
      int shardId = keyToShard(key);
      if (currentConfig.get(groupId).getRight().contains(shardId)
          && !currentManagedShards.contains(shardId)) {
        send(new ShardStoreReply(currentConfigNum, null), command.address());
        return false;
      }
    }

    // Enforce coordinator = min(participants).  If we aren't, tell client.
    Set<Integer> participants = participantsForTransaction(txn);
    if (participants.isEmpty() || groupId != Collections.min(participants)) {
      send(new ShardStoreReply(currentConfigNum, null), command.address());
      return false;
    }

    return true;
  }

  // ---------------- Move/paxos handlers (unchanged from part 3) ----------------
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
    if (m.configNum() != currentConfigNum) {
      return;
    }
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
    if (t.request().configNum() == currentConfigNum && currentManagedShards.contains(t.request().shardId())) {
      broadcast(t.request(), t.destination());
      set(t, MOVE_RETRY_MILLIS);
    }
  }

  /*
   * -----------------------------------------------------------------------------
   * Transaction helpers
   * -----------------------------------------------------------------------------
   */

  // Owner-tracked lock.  Re-entrant for the same AMOCommand so a duplicated
  // PREPARE / coordinator request from a retrying sender doesn't spuriously
  // fail.  Returns true iff the caller now holds the lock.
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

  // For each key in the transaction, find which group currently owns it, per
  // currentConfig.  Result is the set of groupIds that participate in 2PC.
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

  // Run a transaction over the keys whose shards we own.  Used by the
  // coordinator's single-group fast path now, and by the multi-group commit
  // phase later (each participant will run this on its own subset).
  private KVStoreResult executeTransactionOnOwnedShards(Transaction txn) {
    Set<String> ourKeys = new HashSet<>();
    for (String key : txn.keySet()) {
      if (currentManagedShards.contains(keyToShard(key))) {
        ourKeys.add(key);
      }
    }

    // 1. Gather the current values for our keys into a single map.
    Map<String, String> db = new HashMap<>();
    for (String key : ourKeys) {
      Map<String, String> shardStore = shardStoreFor(keyToShard(key));
      if (shardStore.containsKey(key)) {
        db.put(key, shardStore.get(key));
      }
    }

    // 2. Run the transaction (mutates db for keys in writeSet()).
    KVStoreResult result = txn.run(db);

    // 3. Scatter writes back to the per-shard stores.
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

  // Reach into the per-shard TransactionalKVStore's underlying map.  Cast is
  // safe because processNewConfig always installs a TransactionalKVStore.
  private Map<String, String> shardStoreFor(int shardId) {
    TransactionalKVStore tkvs =
        (TransactionalKVStore) app.get(shardId).application();
    return tkvs.store();
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
    if ((!currentConfig.get(groupId).getRight().contains(shardId)) || (!currentManagedShards.contains(shardId))) {
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
          // Use TransactionalKVStore so transactions can run on each shard.
          app.putIfAbsent(shard, new AMOApplication<>(new TransactionalKVStore()));
        }
      }
    } else {
      handleMove();
    }
  }
}
