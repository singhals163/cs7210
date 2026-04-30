package dslabs.shardmaster;

import dslabs.framework.Address;
import dslabs.framework.Application;
import dslabs.framework.Command;
import dslabs.framework.Result;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.PriorityQueue;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.commons.lang3.tuple.Pair;

@ToString
@EqualsAndHashCode
public final class ShardMaster implements Application {
  public static final int INITIAL_CONFIG_NUM = 0;

  private final int numShards;

  // Your code here...
  private Map<Integer, Map<Integer, Pair<Set<Address>, Set<Integer>>>> configs;
  private Map<Integer, Integer> shardToGroupId;
  private int currentConfigNum;

  public ShardMaster(int numShards) {
    this.numShards = numShards;
    this.currentConfigNum = -1;
    this.configs = new HashMap<>();
    this.shardToGroupId = new HashMap<>();
  }

  public interface ShardMasterCommand extends Command {
  }

  @Data
  public static final class Join implements ShardMasterCommand {
    private final int groupId;
    private final Set<Address> servers;
  }

  @Data
  public static final class Leave implements ShardMasterCommand {
    private final int groupId;
  }

  @Data
  public static final class Move implements ShardMasterCommand {
    private final int groupId;
    private final int shardNum;
  }

  @Data
  public static final class Query implements ShardMasterCommand {
    private final int configNum;

    @Override
    public boolean readOnly() {
      return true;
    }
  }

  public interface ShardMasterResult extends Result {
  }

  @Data
  public static final class Ok implements ShardMasterResult {
  }

  @Data
  public static final class Error implements ShardMasterResult {
  }

  @Data
  public static final class ShardConfig implements ShardMasterResult {
    private final int configNum;

    // groupId -> <group members, shard numbers>
    private final Map<Integer, Pair<Set<Address>, Set<Integer>>> groupInfo;
  }

  // =========================================================
  // HELPER METHOD: Deep Copy the Configuration
  // =========================================================
  private Map<Integer, Pair<Set<Address>, Set<Integer>>> deepCopyConfig(
      Map<Integer, Pair<Set<Address>, Set<Integer>>> oldConfig) {

    Map<Integer, Pair<Set<Address>, Set<Integer>>> newConfig = new HashMap<>();

    for (Map.Entry<Integer, Pair<Set<Address>, Set<Integer>>> entry : oldConfig.entrySet()) {
      Integer groupId = entry.getKey();
      // Create brand new sets so we don't mutate the old config's sets
      Set<Address> copiedServers = new HashSet<>(entry.getValue().getLeft());
      Set<Integer> copiedShards = new HashSet<>(entry.getValue().getRight());

      newConfig.put(groupId, Pair.of(copiedServers, copiedShards));
    }
    return newConfig;
  }

  private void balanceConfig() {
    Map<Integer, Pair<Set<Address>, Set<Integer>>> currentConfig = configs.get(currentConfigNum);
    while (true) {
      // move one shard from the largest to the smallest if difference is greater than 1
      Integer largestShard = -1, largestShardSize = 0, smallestShard = -1, smallestShardSize = numShards + 1;
      for (var entry : currentConfig.entrySet()) {
        Integer key = entry.getKey();
        var value = entry.getValue();
        if(value.getRight().size() < smallestShardSize) {
          smallestShardSize = value.getRight().size();
          smallestShard = key;
        }
        if(value.getRight().size() > largestShardSize) {
          largestShardSize = value.getRight().size();
          largestShard = key;
        }
      }
      if(largestShardSize - smallestShardSize <= 1) {
        break;
      }
      Integer moveShard = currentConfig.get(largestShard).getRight().iterator().next();
      shardToGroupId.put(moveShard, smallestShard);
      currentConfig.get(largestShard).getRight().remove(moveShard);
      currentConfig.get(smallestShard).getRight().add(moveShard);
    }
    return;
  }

  @Override
  public Result execute(Command command) {
    if (command instanceof Join) {
      Join join = (Join) command;

      // Your code here...
      if (currentConfigNum == -1) {
        // create the first config
        currentConfigNum = INITIAL_CONFIG_NUM;
        Set<Integer> currentShardSet = new HashSet<>();
        for (Integer i = 1; i <= numShards; i++) {
          shardToGroupId.put(i, join.groupId());
          currentShardSet.add(i);
        }
        Map<Integer, Pair<Set<Address>, Set<Integer>>> currentConfig = new HashMap<>();
        currentConfig.put(join.groupId(), Pair.of(join.servers(), currentShardSet));
        configs.put(currentConfigNum, currentConfig);
        return new Ok();
      } else {
        Map<Integer, Pair<Set<Address>, Set<Integer>>> newConfig = deepCopyConfig(configs.get(currentConfigNum));

        if (newConfig.containsKey(join.groupId())) {
          return new Error();
        }
        currentConfigNum++;

        // Add the new group with empty shards and rebalance
        Set<Integer> newGroupShards = new HashSet<>();
        newConfig.put(join.groupId(), Pair.of(join.servers(), newGroupShards));
        configs.put(currentConfigNum, newConfig);
        balanceConfig();
        return new Ok();
      }
    }

    if (command instanceof Leave) {
      Leave leave = (Leave) command;

      if (currentConfigNum == -1) {
        return new Error();
      }

      Map<Integer, Pair<Set<Address>, Set<Integer>>> newConfig = deepCopyConfig(configs.get(currentConfigNum));

      if (!newConfig.containsKey(leave.groupId())) {
        return new Error();
      }
      currentConfigNum++;

      Set<Integer> moveShards = newConfig.remove(leave.groupId()).getRight();

      // Rebalance using PriorityQueue (Min-Heap)
      if (!newConfig.isEmpty()) {
        PriorityQueue<Pair<Integer, Integer>> pq = new PriorityQueue<>(
            (a, b) -> a.getLeft().compareTo(b.getLeft()));

        for (Map.Entry<Integer, Pair<Set<Address>, Set<Integer>>> entry : newConfig.entrySet()) {
          pq.add(Pair.of(entry.getValue().getRight().size(), entry.getKey()));
        }

        for (Integer shard : moveShards) {
          Pair<Integer, Integer> receiverGroup = pq.poll();
          newConfig.get(receiverGroup.getRight()).getRight().add(shard);
          shardToGroupId.put(shard, receiverGroup.getRight());
          pq.add(Pair.of(receiverGroup.getLeft() + 1, receiverGroup.getRight()));
        }
      } else {
        // If there are no groups left, clear the shard mappings
        shardToGroupId.clear();
      }

      configs.put(currentConfigNum, newConfig);
      balanceConfig();
      return new Ok();
    }

    if (command instanceof Move) {
      Move move = (Move) command;

      if (currentConfigNum == -1) {
        return new Error();
      }

      Integer oldGroupID = shardToGroupId.get(move.shardNum());
      Integer newGroupID = move.groupId();

      // If shard isn't mapped, or we are moving to the same group
      if (oldGroupID == null || oldGroupID.equals(newGroupID)) {
        return new Error();
      }

      Map<Integer, Pair<Set<Address>, Set<Integer>>> newConfig = deepCopyConfig(configs.get(currentConfigNum));

      if (!newConfig.containsKey(newGroupID)) {
        return new Error();
      }

      currentConfigNum++;
      newConfig.get(oldGroupID).getRight().remove(move.shardNum());
      newConfig.get(newGroupID).getRight().add(move.shardNum());
      shardToGroupId.put(move.shardNum(), newGroupID);
      configs.put(currentConfigNum, newConfig);
      return new Ok();
    }

    if (command instanceof Query) {
      Query query = (Query) command;

      if (currentConfigNum == -1) {
        return new Error();
      } else if (query.configNum() == -1 || query.configNum() > currentConfigNum) {
        return new ShardConfig(currentConfigNum, configs.get(currentConfigNum));
      } else if (query.configNum() >= INITIAL_CONFIG_NUM) {
        return new ShardConfig(query.configNum(), configs.get(query.configNum()));
      } else {
        return new Error();
      }
    }

    throw new IllegalArgumentException();
  }
}