package dslabs.primarybackup;

import static dslabs.primarybackup.PingCheckTimer.PING_CHECK_MILLIS;

import com.google.common.base.Objects;
import dslabs.framework.Address;
import dslabs.framework.Node;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.extern.java.Log;
import java.util.Map;
import java.util.HashMap;

@Log
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
class ViewServer extends Node {
  static final int STARTUP_VIEWNUM = 0;
  private static final int INITIAL_VIEWNUM = 1;
  private static final int PING_MISSES_AVAILABLE = 2;

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
    if(m.viewNum() == currentView.viewNum() && Objects.equal(sender, currentView.primary())) {
      primaryAck = true;
    }
    servers.put(sender, PING_MISSES_AVAILABLE);
    generateNewView();
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
    } 
    if(primaryAck == true) {
      if(servers.containsKey(currentView.primary())) {
        primary = currentView.primary();
        if(currentView.backup() != null && servers.containsKey(currentView.backup())) {
          LOG.finest(String.format("exit at A"));
          return;
        }
        for(Map.Entry<Address, Integer> entry: servers.entrySet()) {
          if(!Objects.equal(entry.getKey(), primary)) {
            backup = entry.getKey();
            break;
          }
        }
        if(currentView.backup() != backup) {
          LOG.finest(String.format("exit at B"));
          primaryAck = false;
          currentView = new View(currentView.viewNum() + 1, primary, backup);
        }
        return;
      } else if(servers.containsKey(currentView.backup())) {
        primary = currentView.backup();
        for(Map.Entry<Address, Integer> entry: servers.entrySet()) {
          if(!Objects.equal(entry.getKey(), primary)) {
            backup = entry.getKey();
            break;
          }
        }
        primaryAck = false;
        currentView = new View(currentView.viewNum() + 1, primary, backup);
        LOG.finest(String.format("exit at C"));
        return;
      } else {
        // both are dead, if there is a new server available, make it backup
        primary = currentView.primary();
        for(Map.Entry<Address, Integer> entry: servers.entrySet()) {
          if(!Objects.equal(entry.getKey(), primary)) {
            backup = entry.getKey();
            break;
          }
        }
        if(backup != null) {
          primaryAck = false;
          currentView = new View(currentView.viewNum() + 1, primary, backup);
        }
        LOG.finest(String.format("exit at E"));
        return;
      }
    } else {
      // primary hasn't acked yet, can't do anything
      LOG.finest(String.format("exit at F"));
      return;
    }
  }
}