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
  private int totalGroups;

  public ShardMaster(int numShards) {
    this.numShards = numShards;
    this.currentConfigNum = -1;
    this.totalGroups = 0;
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

  @Override
  public Result execute(Command command) {
    if (command instanceof Join) {
      Join join = (Join) command;

      // Your code here...
      totalGroups++;
      if (currentConfigNum == -1) {
        // create the first config
        currentConfigNum = INITIAL_CONFIG_NUM;
        Set<Integer> currentShardSet = new HashSet<>();
        for (Integer i = 1; i <= numShards; i++) {
          shardToGroupId.put(i, join.groupId());
          currentShardSet.add(i);
        }
        Map<Integer, Pair<Set<Address>, Set<Integer>>> groupInfo = new HashMap<>();
        groupInfo.put(join.groupId(), Pair.of(join.servers(), currentShardSet));
        configs.put(currentConfigNum, groupInfo);
        return new Ok();
      } else {
        // rebalance configs
        Map<Integer, Pair<Set<Address>, Set<Integer>>> currentConfig = configs.get(currentConfigNum);
        if (currentConfig.containsKey(join.groupId())) {
          return new Error();
        }
        currentConfigNum++;

        // create a priority_queue of the number of shards each group is maintaining
        // pop the top and decrease their number one, give one shard from that group to
        // the new group
        // push the group back in the priority_queue
        // keep doing till we find that the top of priority_queue = size of the new
        // group
        PriorityQueue<Pair<Integer, Integer>> pq = new PriorityQueue<>(
            (a, b) -> b.getLeft().compareTo(a.getLeft()));
        currentConfig.forEach((key, value) -> {
          pq.add(Pair.of(value.getRight().size(), key));
        });
        Set<Integer> movedShards = new HashSet<>();
        while (!pq.isEmpty()) {
          Pair<Integer, Integer> donorGroup = pq.poll();
          if (donorGroup.getLeft() - movedShards.size() <= 1 || donorGroup.getLeft() == 1) {
            break;
          }
          Integer movedShard = currentConfig.get(donorGroup.getRight()).getRight().iterator().next();
          shardToGroupId.put(movedShard, join.groupId());
          currentConfig.get(donorGroup.getRight()).getRight().remove(movedShard);
          movedShards.add(movedShard);
          pq.add(Pair.of(donorGroup.getLeft() - 1, donorGroup.getRight()));
        }
        currentConfig.put(join.groupId(), Pair.of(join.servers(), movedShards));
        configs.put(currentConfigNum, currentConfig);
        return new Ok();
      }

    }

    if (command instanceof Leave) {
      Leave leave = (Leave) command;

      // Your code here...
      if (currentConfigNum == -1) {
        return new Error();
      }
      Map<Integer, Pair<Set<Address>, Set<Integer>>> currentConfig = configs.get(currentConfigNum);
      if (!currentConfig.containsKey(leave.groupId())) {
        return new Error();
      }
      currentConfigNum++;

      // make a pq, this time in ascending order, and keep insering element to the
      // top.

      PriorityQueue<Pair<Integer, Integer>> pq = new PriorityQueue<>(
          (a, b) -> a.getLeft().compareTo(b.getLeft()));
      currentConfig.forEach((key, value) -> {
        if(key != leave.groupId()) {
          pq.add(Pair.of(value.getRight().size(), key));
        }
      });
      Set<Integer> moveShards = currentConfig.get(leave.groupId()).getRight();
      for(Integer shard:moveShards) {
        Pair<Integer, Integer> receiverGroup = pq.poll();
        pq.add(Pair.of(receiverGroup.getLeft()+1, receiverGroup.getRight()));
        shardToGroupId.put(shard, receiverGroup.getRight());
        currentConfig.get(receiverGroup.getRight()).getRight().add(shard);
      }
      currentConfig.remove(leave.groupId());
      configs.put(currentConfigNum, currentConfig);
      return new Ok();
    }

    if (command instanceof Move) {
      Move move = (Move) command;

      // Your code here...
      Integer oldGroupID = shardToGroupId.get(move.shardNum());
      Integer newGroupID = move.shardNum();
      if (oldGroupID == newGroupID) {
        return new Error();
      }
      Map<Integer, Pair<Set<Address>, Set<Integer>>> newConfig = configs.get(currentConfigNum);
      currentConfigNum++;
      newConfig.get(oldGroupID).getRight().remove(move.shardNum());
      newConfig.get(newGroupID).getRight().add(move.shardNum());
      shardToGroupId.put(move.shardNum(), newGroupID);
      configs.put(currentConfigNum, newConfig);
      return new Ok();
    }

    if (command instanceof Query) {
      Query query = (Query) command;

      // Your code here...
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
