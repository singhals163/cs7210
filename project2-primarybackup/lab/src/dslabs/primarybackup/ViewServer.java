package dslabs.primarybackup;

import static dslabs.primarybackup.PingCheckTimer.PING_CHECK_MILLIS;

import dslabs.framework.Address;
import dslabs.framework.Node;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.util.Map;
import java.util.HashMap;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
class ViewServer extends Node {
  static final int STARTUP_VIEWNUM = 0;
  private static final int INITIAL_VIEWNUM = 1;
  private static final int PING_MISSES_AVAILABLE = 3;

  // Your code here...
  private Map<Address, Integer> servers = new HashMap<>();
  private View currentView;
  private boolean primaryAck;

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  public ViewServer(Address address) {
    super(address);
  }

  @Override
  public void init() {
    set(new PingCheckTimer(), PING_CHECK_MILLIS);
    // Your code here...
    currentView = new View(STARTUP_VIEWNUM, null, null);
    primaryAck = false;
  }

  /* -----------------------------------------------------------------------------------------------
   *  Message Handlers
   * ---------------------------------------------------------------------------------------------*/
  private void handlePing(Ping m, Address sender) {
    // Your code here...
    if(m.viewNum() == currentView.viewNum() && sender == currentView.primary()) {
      primaryAck = true;
    }
    servers.put(sender, PING_MISSES_AVAILABLE);
    if(currentView.viewNum() == STARTUP_VIEWNUM) {
      generateNewView();
    }
    send(new ViewReply(currentView), sender);
  }
  
  private void handleGetView(GetView m, Address sender) {
    // Your code here...
    send(new ViewReply(currentView), sender);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  private void onPingCheckTimer(PingCheckTimer t) {
    // Your code here...
    set(t, PING_CHECK_MILLIS);
    servers.replaceAll((key, value) -> value - 1);
    servers.entrySet().removeIf(entry -> entry.getValue() == 0);
    if(!servers.containsKey(currentView.primary()) || !servers.containsKey(currentView.backup())) {
      generateNewView();
    }
  }

  /* -----------------------------------------------------------------------------------------------
   *  Utils
   * ---------------------------------------------------------------------------------------------*/
  // Your code here...
  private void generateNewView(){
    Address primary = null, backup = null;
    if(currentView.viewNum() == STARTUP_VIEWNUM) {
      // create the first view
      for(Map.Entry<Address, Integer> entry: servers.entrySet()) {
        if(primary == null) {
          primary = entry.getKey();
        } else {
          backup = entry.getKey();
          break;
        }
      }
      if(primary != null) {
        currentView = new View(INITIAL_VIEWNUM, primary, backup);
      }
      return;
    } else if(primaryAck) {
      if(!servers.containsKey(currentView.primary()) && !servers.containsKey(currentView.backup())) {
        // Both primary and server shouldn't have died, should return an exception here
        return;
      }
      primaryAck = false; 
      primary = currentView.primary();
      if(!servers.containsKey(currentView.primary())) {
        // promote backup as primary
        primary = currentView.backup();
      } 
      for(Map.Entry<Address, Integer> entry: servers.entrySet()) {
        if(entry.getKey() != primary) {
          backup = entry.getKey();
          break;
        }
      }
      currentView = new View(currentView.viewNum() + 1, primary, backup);
    }
    // else {
      // Ack not received from primary 
      // or 
      // primary died, in which no progress in the states
    // }
  }
}