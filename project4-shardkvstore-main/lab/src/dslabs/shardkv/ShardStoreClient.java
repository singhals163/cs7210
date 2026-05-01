package dslabs.shardkv;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;

import com.google.common.base.Objects;

import dslabs.framework.Address;
import dslabs.framework.Client;
import dslabs.framework.Command;
import dslabs.framework.Result;
import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import static dslabs.shardkv.PingTimer.PING_RETRY_MILLIS;
import static dslabs.shardkv.ClientTimer.CLIENT_RETRY_MILLIS;
import dslabs.shardkv.ShardStoreReply;
import dslabs.kvstore.KVStore.*;
import dslabs.paxos.PaxosRequest;
import dslabs.paxos.PaxosReply;
import dslabs.shardmaster.ShardMaster.Query;
import dslabs.shardmaster.ShardMaster.ShardConfig;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class ShardStoreClient extends ShardStoreNode implements Client {
  // Your code here...

  private AMOCommand currentCommand;
  private Result result;
  private int currentConfigNum;
  private int sequenceNum;
  private Map<Integer, Pair<Set<Address>, Set<Integer>>> currentConfig;
  private final String PAXOS_PING_ID;

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
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

  /* -----------------------------------------------------------------------------------------------
   *  Client Methods
   * ---------------------------------------------------------------------------------------------*/
  @Override
  public synchronized void sendCommand(Command command) {
    // Your code here...
    if(!(command instanceof Get || command instanceof Put || command instanceof Append)) {
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
    while(!hasResult())
      wait();
    return result;
  }

  /* -----------------------------------------------------------------------------------------------
   *  Message Handlers
   * ---------------------------------------------------------------------------------------------*/
  private void sendPendingCommand() {
    if (currentCommand == null || result != null || currentConfigNum == -1) {
        return;
    }
    
    String key = ((SingleKeyCommand)currentCommand.command()).key();
    Integer shardId = keyToShard(key);
    
    for (var entry : currentConfig.entrySet()) {
        if (entry.getValue().getRight().contains(shardId)) {
            Address[] destination = entry.getValue().getLeft().toArray(new Address[0]);
            ShardStoreRequest request = new ShardStoreRequest(currentConfigNum, key, currentCommand);
            broadcast(request, destination);
            return;
        }
    }
}

  private synchronized void handleShardStoreReply(ShardStoreReply m, Address sender) {
    // Your code here...
    if(m.configNum() > currentConfigNum) {
      // send getView to viewserver
      sendConfigRequest(-1);
      return;
    } else if(m.configNum() < currentConfigNum || m.result() == null) {
      // TODO: send the command again quickly? fragile logic
      return;
    } else {
      AMOResult res = (AMOResult)(m.result());
      if(currentCommand != null && result == null && res.sequenceNumber() == sequenceNum) {
        this.result = res.result();
        notify();
      }
    }
  }

  void handlePaxosReply(PaxosReply m, Address sender) {
    if (!(PAXOS_PING_ID.equals(m.id()))) {
      // TODO: Do anything?
      return;
    }
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

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
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
