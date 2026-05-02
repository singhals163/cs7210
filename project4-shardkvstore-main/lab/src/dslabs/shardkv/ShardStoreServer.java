package dslabs.shardkv;

import dslabs.framework.Address;
import dslabs.framework.Command;
import dslabs.framework.Application;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import javax.sql.rowset.spi.TransactionalWriter;

import java.util.Map;
import java.util.Objects;

import dslabs.shardmaster.ShardMaster.Query;
import dslabs.shardmaster.ShardMaster.ShardConfig;

import dslabs.atmostonce.AMOApplication;
import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import dslabs.shardkv.ShardStoreReply;
import dslabs.paxos.PaxosReply;
import dslabs.paxos.PaxosRequest;
import dslabs.kvstore.KVStore;
import org.apache.commons.lang3.tuple.Pair;
import static dslabs.shardkv.PingTimer.PING_RETRY_MILLIS;
import dslabs.shardkv.PingTimer;
import static dslabs.shardkv.MoveTimer.MOVE_RETRY_MILLIS;
import dslabs.shardkv.MoveTimer;
import dslabs.paxos.PaxosDecision;
import dslabs.paxos.PaxosServer;
import dslabs.kvstore.KVStore.*;
import dslabs.kvstore.TransactionalKVStore.*;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class ShardStoreServer extends ShardStoreNode {
  private final Address[] group;
  private final int groupId;

  // Your code here...
  private static final String PAXOS_ADDRESS_ID = "paxos";
  private static final String PAXOS_PING_ID = "paxos-ping";
  private Address paxosAddress;
  private Map<Integer, AMOApplication<Application>> app;
  private Map<Integer, Pair<Set<Address>, Set<Integer>>> currentConfig;
  private Integer currentConfigNum = -1;
  private Set<Integer> currentManagedShards;
  private boolean serverLocked;
  private Set<Integer> currentTransactionGroupIds;
  private Set<Integer> prepareTransactionGroupIds;
  private AMOCommand currentTransaction;

  /*
   * -----------------------------------------------------------------------------
   * ------------------
   * Construction and Initialization
   * -----------------------------------------------------------------------------
   * ----------------
   */
  ShardStoreServer(
      Address address, Address[] shardMasters, int numShards, Address[] group, int groupId) {
    super(address, shardMasters, numShards);
    this.group = group;
    this.groupId = groupId;

    // Your code here...
    this.app = new HashMap<>();
    this.currentManagedShards = new HashSet<>();
    this.serverLocked = false;
    this.currentTransactionGroupIds = new HashSet<>();
    this.prepareTransactionGroupIds = new HashSet<>();
  }

  @Override
  public void init() {
    // Your code here...
    // TODO: send a config query to shardmaster start a timer with shardmaster
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
   * ------------------
   * Message Handlers
   * -----------------------------------------------------------------------------
   * ----------------
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

  // The handleX functions and processX functions are exactly the same except for
  // handleX doesn't really make changes in the server's data structures.
  private void handleShardStoreRequest(ShardStoreRequest m, Address sender) {
    if (m.command().command() instanceof SingleKeyCommand) {
      handleShardStoreSingleKeyRequest(m, sender);
    } else {
      handleShardStoreMultiKeyRequest(m, sender);
      // TODO: handle multiple replica servers later. for now, let's try the single
      // server approach

    }
  }

  /*
  Find the intersection of shardIds with currentManagedShards: call it shardsToLock
  Take a lock on each of the shards in shardsToLock
  if lock taken successfully: 
    - return true
    - release all locks that were successful, return false
  We can initially do it at a server level too, lock the entire server, which is easier to implement 
  */
  private boolean takeLocks() {
    // TODO: write this implementation
    if(serverLocked) return false;
    serverLocked = true;
    return true;
  }

  /*
  CRITICAL: Ensure that the one who took the lock can only release it
  */
  private void releaseLock() {
    serverLocked = false;
  }

  /*
  handleShardStoreMultiKeyRequest()
   * // Step 1. Match configNum and perform other checks
   * // Step 2. Take locks for our shards
   * // Step 3. Send Prepare request to other servers
   * // Step 4. Start a timer
   */
  private void handleShardStoreMultiKeyRequest(ShardStoreRequest m, Address sender) {
    Command command = (AMOCommand)(m.command());
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
    Set<String> keys = ((Transaction)command.command()).keySet();
    Set<Integer> shardsIds = new HashSet<>();
    for (String key : keys) {
      shardsIds.add(keyToShard(key));
    }
    Set<Integer> groupIds;
    for (var entry : currentConfig.entrySet()) {
      if (!(Collections.disjoint(entry.getValue().getRight(), shardsIds))) {
        groupIds.add(entry.getKey());
      }
    }
    if(!(groupIds.contains(groupId)) || !isStable()) {
      send(new ShardStoreReply(currentConfigNum, null), command.address());
      return;
    }
    Set<Integers> myShardsToLock = new HashSet<>(currentManagedShards);
    if(takeLocks(myShardsToLock.retainAll(shardIds))) {
      // TODO: send prepare requests to other servers
      for(Integer otherGroupId:groupIds){
        Address[] destination = currentConfig.get(otherGroupId).getLeft().toArray(new Address[0]);
        PrepareTransactionRequest request = new PrepareTransactionRequest(currentConfigNum, command, group);
        broadcast(request, destination);
        currentTransactionGroupIds = groupIds;
        currentTransaction = command;
        // TODO: start relevant timers
        set(new PrepareTimer(destination, reqeust), PREPARE_RETRY_MILLIS);
      }
    } else {
      send(new ShardStoreReply(currentConfigNum, null), command.address());
      return;
    }
  }


  /*
   * handlePrepareTransactionRequest()
   * Match confignum, if doesn't match send a reject message
   * Take lock on shards:
   * - if taken, send prepare accept message
   * - if already locked
   * - send a reject message
   */
  private void handlePrepareTransactionRequest(PrepareTransactionRequest m, Address sender) {
    Command command = (AMOCommand)(m.command());
    if (m.configNum() != currentConfigNum) {
      broadcast(new PrepareTransactionReply(currentConfigNum, false, groupId), m.senders());
      if (m.configNum() > currentConfigNum) {
        sendConfigRequest(currentConfigNum + 1);
      }
      return;
    }
    if (currentConfig == null || !currentConfig.containsKey(groupId)) {
      broadcast(new PrepareTransactionReply(currentConfigNum, false, groupId), m.senders());
      return;
    }
    Set<String> keys = ((Transaction)command.command()).keySet();
    Set<Integer> shardsIds = new HashSet<>();
    for (String key : keys) {
      shardsIds.add(keyToShard(key));
    }
    if(!isStable() || Collections.disjoint(shardIds, currentManagedShards)) {
      broadcast(new PrepareTransactionReply(currentConfigNum, false, groupId), m.senders());
      return;
    }
    if(takeLocks()) {
      broadcast(new PrepareTransactionReply(currentConfigNum, true, groupId), m.senders());
      return;
    } else {
      broadcast(new PrepareTransactionReply(currentConfigNum, false, groupId), m.senders());
      return;
    }
  }

  /*
   * handlePrepareTransactionReply()
   * - if reject, abort transaction, release locks, send abort request
   * - if accept, add that to the set of accepts
   * - if accept set complete, start commit phase
   */
  private void handlePrepareTransactionReply(PrepareTransactionReply m, Address sender) {
    if(m.result()) {
      prepareTransactionGroupIds.add(m.groupId());
      // add group id to the current transactions groups
      beginCommitPhase();
    } else {
      // abort transaction
      beginAbortPhase();
    }
  }


  /*
   * beginCommitPhase()
   * - send commit request to other servers and start timers
   */
  private void beginCommitPhase() {
    if(Objects.equals(currentTransactionGroupIds, prepareTransactionGroupIds)) {

    }
  }

  /*
   * handleCommitRequest()
   * - commit changes if not already commited and send commit reply
   */

  /*
   * handleCommitReply()
   * - put commit reply to the list of accepted commits
   * - when the set is complete reply with ack to the client
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
    if (m.configNum() != currentConfigNum) {
      return;
    }
    if (currentManagedShards.contains(m.shardId())) {
      handleMessage(new PaxosRequest("shardMoveAck-" + m.shardId() + "-" + m.configNum(),
          m.configNum(), new ShardMoveAckCmd(m)), paxosAddress);
    }
  }

  void handlePaxosReply(PaxosReply m, Address sender) {
    if (!(PAXOS_PING_ID.equals(m.id())))
      return;
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
   * ------------------
   * Timer Handlers
   * -----------------------------------------------------------------------------
   * ----------------
   */
  // Your code here...
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

  void onPrepareTimer(PrepareTimer t) {
    if(t.request().configNum() == currentConfigNum && )
  }

  /*
   * -----------------------------------------------------------------------------
   * ------------------
   * Utils
   * -----------------------------------------------------------------------------
   * ----------------
   */
  // Your code here...
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
        Integer key = entry.getKey();
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
    if (currentConfigNum == -1)
      return true;

    if (!currentConfig.containsKey(groupId)) {
      return currentManagedShards.isEmpty();
    }
    return currentManagedShards.equals(currentConfig.get(groupId).getRight());
  }

  private void processKVRequest(ShardStoreRequest m) {
    AMOCommand command = (AMOCommand) m.command();
    // Defense-in-depth: pre-checks in handleShardStoreRequest mean we should
    // never enter this method with a mismatched config in single-server local
    // paxos. In reference multi-server paxos a NewConfigCmd can be decided
    // between propose and decide, so we still send a reply (rather than the
    // old silent `return`) so the client never hangs waiting on a slot whose
    // decision will never be re-delivered by paxos's dedup.
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
    // TODO: fix this
    if (m.configNum() > currentConfigNum) {
      sendConfigRequest(currentConfigNum + 1);
    }
    if (m.configNum() != currentConfigNum) {
      return;
    }
    if (currentManagedShards.contains(m.shardId())) {
      currentManagedShards.remove(m.shardId());
      app.remove(m.shardId());
    }
  }

  void processNewConfig(ShardConfig newConfig) {
    // TODO: fix this
    if (newConfig.configNum() == currentConfigNum + 1) {
      currentConfigNum = newConfig.configNum();
      currentConfig = newConfig.groupInfo();
      if (currentConfigNum == 0) {
        if (currentConfig.containsKey(groupId)) {
          currentManagedShards.addAll(currentConfig.get(groupId).getRight());
          for (Integer shard : currentManagedShards) {
            // TODO: have to make it transactionalKVStore
            app.putIfAbsent(shard, new AMOApplication<>(new KVStore()));
          }
        }
      } else {
        handleMove();
      }
    }
  }

}
