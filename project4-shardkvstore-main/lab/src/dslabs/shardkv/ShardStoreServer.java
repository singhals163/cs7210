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

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class ShardStoreServer extends ShardStoreNode {
  private final Address[] group;
  private final int groupId;

  // Your code here...
  private final String PAXOS_ADDRESS_ID;
  private final String PAXOS_PING_ID;
  private Address paxosAddress;
  private Map<Integer, AMOApplication<Application>> app;
  private Map<Integer, Pair<Set<Address>, Set<Integer>>> currentConfig;
  private Integer currentConfigNum = -1;
  private Set<Integer> currentManagedShards;
  private Set<Integer> shardsToMove;

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
    PAXOS_ADDRESS_ID = "paxos-" + groupId;
    PAXOS_PING_ID = "paxos-ping-" + groupId;

    // Your code here...
    this.app = new HashMap<>();
    this.currentManagedShards = new HashSet<>();
    this.shardsToMove = new HashSet<>();

  }

  @Override
  public void init() {
    // Your code here...
    // TODO: send a config query to shardmaster start a timer with shardmaster
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
    if (m.configNum() > currentConfigNum) {
      sendConfigRequest(-1);
      return;
    } else if (m.configNum() < currentConfigNum) {
      // send a reply?
      return;
    }
    Integer shardId = keyToShard(m.key());
    if ((!currentConfig.get(groupId).getRight().contains(shardId)) || (!currentManagedShards.contains(shardId))) {
      // TODO: send a reply?
      // Maybe push these in a queue meanwhile if they've reached the right server?
      return;
    }
    AMOResult result = app.get(shardId).execute(m.command());

    send(new ShardStoreReply(currentConfigNum, result), sender);
  }

  private void handleMoveRequest(MoveRequest m, Address sender) {
    if (m.configNum() > currentConfigNum) {
      sendConfigRequest(-1);
    }
    if (m.configNum() != currentConfigNum) {
      return;
    }
    if (currentConfig.get(groupId).getRight().contains(m.shardId())) {
      if (!currentManagedShards.contains(m.shardId())) {
        app.put(m.shardId(), m.app());
        currentManagedShards.add(m.shardId());
      }
      send(new MoveReply(currentConfigNum, m.shardId()), sender);
    }
  }

  private void handleMoveReply(MoveReply m, Address sender) {
    if (m.configNum() > currentConfigNum) {
      sendConfigRequest(-1);
    }
    if (m.configNum() != currentConfigNum) {
      return;
    }
    if (currentManagedShards.contains(m.shardId())) {
      currentManagedShards.remove(m.shardId());
      app.remove(m.shardId());
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
    sendConfigRequest(-1);
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

  void handlePaxosReply(PaxosReply m, Address sender) {
    if (!(m.id() == PAXOS_PING_ID)) {
      // TODO: Do anything?
      return;
    }
    if (m.result() instanceof ShardConfig) {
      ShardConfig newConfig = (ShardConfig) m.result();
      if (newConfig.configNum() > currentConfigNum) {
        currentConfigNum = newConfig.configNum();
        currentConfig = newConfig.groupInfo();
        if (currentConfigNum == 0) {
          if (currentConfig.containsKey(groupId)) {
            currentManagedShards = currentConfig.get(groupId).getRight();
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

          MoveRequest request = new MoveRequest(currentConfigNum, shardId, moveApp);
          set(new MoveTimer(value.getLeft().toArray(new Address[0]), request), MOVE_RETRY_MILLIS);
        }
      }
    }
  }

}
