package dslabs.shardkv;

import dslabs.framework.Address;
import dslabs.framework.Command;
import dslabs.framework.Application;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;

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
  private void handleShardStoreRequest(ShardStoreRequest m, Address sender) {
    // Your code here...
    handleMessage(new PaxosRequest(sender.toString(), m.command().sequenceNumber(), new ShardStoreCommand(m)), paxosAddress);
  }

  private void handleMoveRequest(MoveRequest m, Address sender) {
    handleMessage(new PaxosRequest("shardMove-"+m.shardId(), m.configNum(), new ShardMoveCmd(m)), paxosAddress);
  }

  private void handleMoveReply(MoveReply m, Address sender) {
    handleMessage(new PaxosRequest("shardMoveAck-" + m.shardId(), m.configNum(), new ShardMoveAckCmd(m)), paxosAddress);
  }

  void handlePaxosReply(PaxosReply m, Address sender) {
    if (!(PAXOS_PING_ID.equals(m.id())))
      return;
    if (m.result() instanceof ShardConfig) {
      ShardConfig newConfig = (ShardConfig) m.result();

      if (newConfig.configNum() == currentConfigNum + 1 && isStable()) {
        handleMessage(new PaxosRequest("newConfig", newConfig.configNum(), new NewConfigCmd(newConfig)),
            paxosAddress);
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
    // TODO: fix this
    AMOCommand command = (AMOCommand) m.command();
    if (m.configNum() > currentConfigNum) {
      sendConfigRequest(currentConfigNum + 1);
      return;
    } else if (m.configNum() < currentConfigNum) {
      send(new ShardStoreReply(currentConfigNum, null), command.address());
      return;
    }
    if (!currentConfig.containsKey(groupId)) {
      return;
    }
    Integer shardId = keyToShard(m.key());
    if ((!currentConfig.get(groupId).getRight().contains(shardId)) || (!currentManagedShards.contains(shardId))) {
      // TODO: send a reply?
      // Maybe push these in a queue meanwhile if they've reached the right server?
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
            app.putIfAbsent(shard, new AMOApplication<>(new KVStore()));
          }
        }
      } else {
        handleMove();
      }
    }
  }

}
