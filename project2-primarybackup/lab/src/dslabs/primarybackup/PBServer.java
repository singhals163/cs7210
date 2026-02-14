package dslabs.primarybackup;

import com.google.common.base.Objects;
import static dslabs.primarybackup.PingTimer.PING_MILLIS;
import static dslabs.primarybackup.InitTimer.INIT_MILLIS;
import static dslabs.primarybackup.PBCommandTimer.PB_COMMAND_MILLIS;
import dslabs.framework.Address;
import dslabs.framework.Application;
import dslabs.framework.Node;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import dslabs.atmostonce.AMOApplication;
import java.util.Queue;
import java.net.Authenticator.RequestorType;
import java.util.LinkedList;
import dslabs.atmostonce.*;
import dslabs.kvstore.KVStore.*;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
class PBServer extends Node {
  private final Address viewServer;

  // Your code here...
  private boolean backupReady;
  private View currentView;
  private final AMOApplication<Application> app;
  private Queue<AMOCommand> clientRequests = new LinkedList<>(); 

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  PBServer(Address address, Address viewServer, Application app) {
    super(address);
    this.viewServer = viewServer;

    // Your code here...
    backupReady = false;
    currentView = new View(ViewServer.STARTUP_VIEWNUM, null, null);
    this.app = new AMOApplication<>(app);
  }

  @Override
  public void init() {
    // Your code here...
    send(new Ping(currentView.viewNum()), viewServer);
    set(new PingTimer(), PING_MILLIS);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Message Handlers
   * ---------------------------------------------------------------------------------------------*/
  // private void handleRequest(Request m, Address sender) {
  //   // Your code here...
  //   if(m instanceof CSRequest) {
  //     if(isPrimary) {
  //       // execute request
  //       // send the request to the backup
  //       // wait for response
  //       // if succeed, return
  //       // if not,        
  //       AMOResult result = app.execute(m.command());
  //       send(new Reply(result), sender);
  //     } else {
  //       // can't take reqeusts from clients, return error
  //       // TODO: need to send an AMOResult? 
  //       send(new PBReply(CSError), sender);
  //     }
  //   } else if(m instanceof PBRequest) {

  //   }
  // }

  private void handleViewReply(ViewReply m, Address sender) {
    // Your code here...
    View reply = m.view();
    if(currentView.viewNum() < reply.viewNum()) {
      currentView = reply;
      backupReady = false;
      if(currentView.primary() == this.address()) {
        startBackupInit();
        if (currentView.backup() == null) {
          send(new Ping(currentView.viewNum()), viewServer);
        }
      } 
    }
  }

  // Your code here...

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  private void onPingTimer(PingTimer t) {
    // Your code here...
    if (currentView.primary() == this.address() && currentView.backup() != null && !backupReady) {
      send(new Ping(currentView.viewNum() - 1), viewServer);
    } else {
      send(new Ping(currentView.viewNum()), viewServer);
    }
    set(t, PING_MILLIS);
  }

  // Your code here...

  /* -----------------------------------------------------------------------------------------------
   *  Utils
   * ---------------------------------------------------------------------------------------------*/
  // Your code here...
  



  /* -----------------------------------------------------------------------------------------------
   * Init backup handling logic
   * ---------------------------------------------------------------------------------------------*/
 
  private void startBackupInit() {
    // If currentView has a backup, send a generateInitCommand to the backup
    if(currentView.backup() != null && backupReady == false) {
      AMOResult result = app.execute(new AMOCommand(new GetInit(), 1, this.address()));
      AMOCommand command = new AMOCommand(new Init(((GetInitResult)result.result()).store()), 1, this.address());
      PBInitRequest req = new PBInitRequest(currentView.viewNum(), command);
      send(req, currentView.backup());
      set(new InitTimer(currentView.viewNum(), req), INIT_MILLIS);
    }
  }
  
  private void onInitTimer(InitTimer t) {
    if(t.viewNum() != currentView.viewNum()) return;
    if(backupReady == false) {
      send(t.request(), currentView.backup());
      set(t, INIT_MILLIS);
    }
  } 

  // if a backup and the view is same as primary, 
  // accept the request and return an OK reply
  // else return an error
  private void handlePBInitRequest(PBInitRequest m, Address sender) {
    if(currentView.viewNum() != m.viewNum()) {
      send(new PBInitReply(currentView.viewNum(), null), sender);
      if(currentView.viewNum() < m.viewNum()) {
        // sending a new ping as my view is old
        send(new Ping(currentView.viewNum()), viewServer);
      }
      return;
    }
    AMOResult result = app.execute(m.command());
    send(new PBInitReply(currentView.viewNum(), result), sender);
  }
  private void handlePBInitReply(PBInitReply m, Address sender) {
    if(m.viewNum() > currentView.viewNum()) {
      // send Ping to viewserver
      send(new Ping(currentView.viewNum()), viewServer);
    } else if(m.viewNum() < currentView.viewNum() || m.result() == null) {
      // TODO: send the command again
      return;
    } else {
      if(m.viewNum() == currentView.viewNum() && m.result() != null) {
        backupReady = true;
        send(new Ping(currentView.viewNum()), viewServer);
        sendNextPBCommand();
        set(new PBCommandTimer(currentView.viewNum()), PB_COMMAND_MILLIS);
      }
    }
  }

  /* -----------------------------------------------------------------------------------------------
   * Primary-Backup update messages
   * ---------------------------------------------------------------------------------------------*/
  private void sendNextPBCommand() {
    AMOCommand c = clientRequests.peek();
    if(c != null && currentView != null && currentView.backup() != null) {
      send(new PBCommandRequest(currentView.viewNum(), c), currentView.backup());
    }
  }
  
  private void onPBCommandTimer(PBCommandTimer t) {
    if(t.viewNum() != currentView.viewNum()) return;
    sendNextPBCommand();
    set(t, PB_COMMAND_MILLIS);
  }

  private void handlePBCommandRequest(PBCommandRequest m, Address sender) {
    if(currentView.viewNum() != m.viewNum()) {
      send(new PBCommandReply(currentView.viewNum(), null, null), sender);
      if(currentView.viewNum() < m.viewNum()) {
        send(new Ping(currentView.viewNum()), viewServer);
      }
      return;
    }
    AMOResult result = app.execute(m.command());
    send(new PBCommandReply(currentView.viewNum(), result, m.command()), sender);
  }

  private void handlePBCommandReply(PBCommandReply m, Address sender) {
    if(m.viewNum() > currentView.viewNum()) {
      send(new Ping(currentView.viewNum()), viewServer);
    } else if(m.viewNum() < currentView.viewNum() || m.result() == null) {
      sendNextPBCommand();
    } else {
      AMOCommand c = clientRequests.peek();
      if (!Objects.equal(c, m.command())) return;
      clientRequests.remove();
      send(new CSReply(currentView.viewNum(), m.result()), c.address());
      sendNextPBCommand();
    }
  }

  /* -----------------------------------------------------------------------------------------------
   * Client-server requests handling logic
   * ---------------------------------------------------------------------------------------------*/


  private void handleCSRequest(CSRequest m, Address sender) {
    if(m.viewNum() != currentView.viewNum()) {
      send(new CSReply(currentView.viewNum(), null), sender);
      if(currentView.viewNum() < m.viewNum()) {
        send(new Ping(currentView.viewNum()), viewServer);
      }
      return;
    }

    AMOResult result = app.execute(m.command());
    if(((AMOCommand)m.command()).command() instanceof Get || currentView.backup() == null) {
      send(new CSReply(currentView.viewNum(), result), sender);
      return;
    }
    clientRequests.add((AMOCommand)m.command());
    sendNextPBCommand();
  }


}
