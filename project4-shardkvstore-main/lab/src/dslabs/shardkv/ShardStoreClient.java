package dslabs.shardkv;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;

import dslabs.framework.Address;
import dslabs.framework.Client;
import dslabs.framework.Command;
import dslabs.framework.Result;
import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import dslabs.kvstore.KVStore.Append;
import dslabs.kvstore.KVStore.Get;
import dslabs.kvstore.KVStore.Put;
import dslabs.kvstore.KVStore.SingleKeyCommand;
import dslabs.kvstore.TransactionalKVStore.Transaction;
import dslabs.paxos.PaxosRequest;
import dslabs.paxos.PaxosReply;
import dslabs.shardmaster.ShardMaster.Query;
import dslabs.shardmaster.ShardMaster.ShardConfig;

import static dslabs.shardkv.PingTimer.PING_RETRY_MILLIS;
import static dslabs.shardkv.ClientTimer.CLIENT_RETRY_MILLIS;

import lombok.EqualsAndHashCode;
import lombok.ToString;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class ShardStoreClient extends ShardStoreNode implements Client {
  private AMOCommand currentCommand;
  private Result result;
  private int currentConfigNum;
  private int sequenceNum;
  private Map<Integer, Pair<Set<Address>, Set<Integer>>> currentConfig;
  private final String PAXOS_PING_ID;

  /*
   * -----------------------------------------------------------------------------
   * ------------------
   * Construction and Initialization
   * -----------------------------------------------------------------------------
   * ----------------
   */
  public ShardStoreClient(Address address, Address[] shardMasters, int numShards) {
    super(address, shardMasters, numShards);
    currentConfigNum = -1;
    sequenceNum = 0;
    currentConfig = new HashMap<>();
    PAXOS_PING_ID = "PAXOS-PING-CLIENT";
  }

  @Override
  public synchronized void init() {
    // Your code here...
    sendConfigRequest(-1);
    set(new PingTimer(), PING_RETRY_MILLIS);
  }

  /*
   * -----------------------------------------------------------------------------
   * ------------------
   * Client Methods
   * -----------------------------------------------------------------------------
   * ----------------
   */
  @Override
  public synchronized void sendCommand(Command command) {
    // Your code here...
    if (!(command instanceof Get
        || command instanceof Put
        || command instanceof Append
        || command instanceof Transaction)) {
      throw new IllegalArgumentException();
    }
    sequenceNum++;
    currentCommand = new AMOCommand(command, sequenceNum, this.address());
    result = null;

    set(new ClientTimer(sequenceNum), CLIENT_RETRY_MILLIS);
    sendPendingCommand();
  }

  @Override
  public synchronized boolean hasResult() {
    // Your code here...
    return result != null;
  }

  @Override
  public synchronized Result getResult() throws InterruptedException {
    // Your code here...
    while (!hasResult())
      wait();
    return result;
  }

  /*
   * -----------------------------------------------------------------------------
   * ------------------
   * Message Handlers
   * -----------------------------------------------------------------------------
   * ----------------
   */
  private void sendPendingCommand() {
    if (currentCommand == null || result != null || currentConfigNum == -1) {
      return;
    }
    Command inner = currentCommand.command();
    if (inner instanceof SingleKeyCommand) {
      sendSingleKey((SingleKeyCommand) inner);
    } else if (inner instanceof Transaction) {
      sendTransaction((Transaction) inner);
    }
  }

  private void sendSingleKey(SingleKeyCommand cmd) {
    String key = cmd.key();
    int shardId = keyToShard(key);
    for (var entry : currentConfig.entrySet()) {
      if (entry.getValue().getRight().contains(shardId)) {
        Address[] dest = entry.getValue().getLeft().toArray(new Address[0]);
        broadcast(new ShardStoreRequest(currentConfigNum, key, currentCommand), dest);
        return;
      }
    }
  }

  // Send a transaction to the coordinator group only — defined as the lowest
  // groupId among the participants (the groups owning at least one key in the
  // transaction).  This matches the server-side coordinator check; sending
  // to anyone else gets an immediate null reply.
  private void sendTransaction(Transaction txn) {
    Integer coordinator = coordinatorGroupId(txn);
    if (coordinator == null) return;   // config doesn't yet cover all keys
    Address[] dest = currentConfig.get(coordinator).getLeft().toArray(new Address[0]);
    // key is irrelevant for a Transaction; pass null.
    broadcast(new ShardStoreRequest(currentConfigNum, null, currentCommand), dest);
  }

  private Integer coordinatorGroupId(Transaction txn) {
    Set<Integer> participants = new HashSet<>();
    for (String key : txn.keySet()) {
      int shardId = keyToShard(key);
      Integer owner = ownerForShard(shardId);
      if (owner == null) return null;
      participants.add(owner);
    }
    if (participants.isEmpty()) return null;
    return Collections.min(participants);
  }

  private Integer ownerForShard(int shardId) {
    for (var entry : currentConfig.entrySet()) {
      if (entry.getValue().getRight().contains(shardId)) {
        return entry.getKey();
      }
    }
    return null;
  }

  private synchronized void handleShardStoreReply(ShardStoreReply m, Address sender) {
    if (m.configNum() > currentConfigNum) {
      sendConfigRequest(-1);
      return;
    } else if (m.configNum() < currentConfigNum || m.result() == null) {
      // Wait for ClientTimer / PingTimer; do not fast-retry (see part-3 review).
      return;
    } else {
      AMOResult res = m.result();
      if (currentCommand != null && result == null && res.sequenceNumber() == sequenceNum) {
        this.result = res.result();
        notify();
      }
    }
  }

  void handlePaxosReply(PaxosReply m, Address sender) {
    if (!PAXOS_PING_ID.equals(m.id())) return;
    if (m.result() instanceof ShardConfig) {
      ShardConfig newConfig = (ShardConfig) m.result();
      if (newConfig.configNum() > currentConfigNum) {
        currentConfigNum = newConfig.configNum();
        currentConfig = newConfig.groupInfo();
        sendPendingCommand();
      }
    }
  }

  // Your code here...

  /*
   * -----------------------------------------------------------------------------
   * ------------------
   * Timer Handlers
   * -----------------------------------------------------------------------------
   * ----------------
   */
  private synchronized void onClientTimer(ClientTimer t) {
    // Your code here...
    if (currentCommand != null && result == null && t.sequenceNum() == sequenceNum) {
      sendPendingCommand();
      set(t, CLIENT_RETRY_MILLIS);
    }
  }

  private synchronized void onPingTimer(PingTimer t) {
    sendConfigRequest(-1);
    set(t, PING_RETRY_MILLIS);
  }

  void sendConfigRequest(Integer configNum) {
    broadcastToShardMasters(new PaxosRequest(PAXOS_PING_ID, 0, new Query(configNum)));
  }
}
