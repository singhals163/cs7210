package dslabs.shardkv;

import dslabs.framework.Address;
import dslabs.framework.Command;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import dslabs.atmostonce.AMOApplication;
import dslabs.atmostonce.AMOCommand;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class ShardStoreServer extends ShardStoreNode {
  private final Address[] group;
  private final int groupId;

  // Your code here...
  private static final String PAXOS_ADDRESS_ID = "paxos";
  private Address paxosAddress;
  private AMOApplication<Application> app;
  private Map<Integer, Pair<Set<Address>, Set<Integer>>> currentConfig;
  private Integer currentConfigNum = -1;


  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  ShardStoreServer(
      Address address, Address[] shardMasters, int numShards, Address[] group, int groupId) {
    super(address, shardMasters, numShards);
    this.group = group;
    this.groupId = groupId;

    // Your code here...
    this.app = new AMOApplication<>();

  }

  @Override
  public void init() {
    // Your code here...
    // TODO: send a config query to shardmaster start a timer with shardmaster

  }

  /* -----------------------------------------------------------------------------------------------
   *  Message Handlers
   * ---------------------------------------------------------------------------------------------*/
  private void handleShardStoreRequest(ShardStoreRequest m, Address sender) {
    // Your code here...
    //  

  }

  // Your code here...
  private void process(Command command, boolean replicated) {
    // TODO: manage replicated servers
    if(command instanceof AMOCommand) {
      app.execute((AMOCommand)command);
    } else if (command instanceof ShradMove) {

    } else if(command instanceof ShardMoveAck) {
      
    } else if(command instanceof NewConfig) {

    }
  }

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  // Your code here...
  void onPingTimer(PingTimer t) {
    send(new PaxosRequest())
    set(t, PING_RETRY_MILLIS);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Utils
   * ---------------------------------------------------------------------------------------------*/
  // Your code here...
}
