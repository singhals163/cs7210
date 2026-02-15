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
  private static class ServerInfo {
    int missesRemaining;
    int maxViewNumSeen;

    ServerInfo(int misses, int viewNum) {
      this.missesRemaining = misses;
      this.maxViewNumSeen = viewNum;
    }
  }

  private final Map<Address, ServerInfo> servers = new HashMap<>();
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
    ServerInfo info = servers.get(sender);
    if (info == null) {
      servers.put(sender, new ServerInfo(PING_MISSES_AVAILABLE, m.viewNum()));
    } else {
      if (m.viewNum() < info.maxViewNumSeen) {
        return;
      }
      info.missesRemaining = PING_MISSES_AVAILABLE;
      info.maxViewNumSeen = m.viewNum();
    }

    if (m.viewNum() == currentView.viewNum() && sender.equals(currentView.primary())) {
      primaryAck = true;
    }
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
    for (ServerInfo info : servers.values()) {
      if (info.missesRemaining > 0) {
        info.missesRemaining--;
      }
    }
    generateNewView();
    set(t, PING_CHECK_MILLIS);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Utils
   * ---------------------------------------------------------------------------------------------*/
  // Your code here...
  private boolean isAlive(Address a) {
    return a != null && servers.containsKey(a) && servers.get(a).missesRemaining > 0;
  }
  
  private void generateNewView() {
    if (currentView.viewNum() == STARTUP_VIEWNUM) {
      Address primary = null;
      Address backup = null;

      for (Address a : servers.keySet()) {
        if (!isAlive(a)) continue;
        if (primary == null) primary = a;
        else if (backup == null) backup = a;
        else break; 
      }

      if (primary != null) {
        currentView = new View(INITIAL_VIEWNUM, primary, backup);
        primaryAck = false; // New view needs new ack
      }
      return;
    }

    if (!primaryAck) {
      return;
    }

    Address currentPrimary = currentView.primary();
    Address currentBackup = currentView.backup();

    Address newPrimary = currentPrimary;
    Address newBackup = currentBackup;

    if (!isAlive(currentPrimary) && isAlive(currentBackup)) {
      newPrimary = currentBackup; 
      newBackup = null; 
    }
    if (!isAlive(newBackup)) {
      newBackup = null;
    }
    if (newBackup == null) {
      for (Address a : servers.keySet()) {
        if (isAlive(a) && !a.equals(newPrimary)) {
          newBackup = a;
          break;
        }
      }
    }

    boolean primaryChanged = (newPrimary != null && !newPrimary.equals(currentView.primary())) || (newPrimary == null && currentView.primary() != null);
    boolean backupChanged = (newBackup != null && !newBackup.equals(currentView.backup())) || (newBackup == null && currentView.backup() != null);

    if (primaryChanged || backupChanged) {
        if (newPrimary != null) {
            currentView = new View(currentView.viewNum() + 1, newPrimary, newBackup);
            primaryAck = false;
        }
    }
  }
}