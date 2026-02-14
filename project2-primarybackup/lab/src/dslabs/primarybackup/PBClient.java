package dslabs.primarybackup;

import com.google.common.base.Objects;
import dslabs.framework.Address;
import dslabs.framework.Client;
import dslabs.framework.Command;
import dslabs.framework.Node;
import dslabs.framework.Result;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import dslabs.kvstore.KVStore.*;
import dslabs.atmostonce.*;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
class PBClient extends Node implements Client {
  private final Address viewServer;

  // Your code here...
  private Request request;
  private Result result;
  private View currentView;
  private int sequenceNum = 0;

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  public PBClient(Address address, Address viewServer) {
    super(address);
    this.viewServer = viewServer;
  }

  @Override
  public synchronized void init() {
    // Your code here...
    send(new GetView(), viewServer);
    set(new PingTimer(), PingTimer.PING_MILLIS);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Client Methods
   * ---------------------------------------------------------------------------------------------*/
  @Override
  public synchronized void sendCommand(Command command) {
    // Your code here...
    if(currentView == null || currentView.primary() == null) {
      return;
    } 
    if(command instanceof Get || command instanceof Put || command instanceof Append) {
      sequenceNum++;
      AMOCommand amoCommand = new AMOCommand(command, sequenceNum, this.address());

      request = new CSRequest(currentView.viewNum(), amoCommand);
      send(request, currentView.primary());
      set(new ClientTimer(request), ClientTimer.CLIENT_RETRY_MILLIS);
    } else {
      throw new IllegalArgumentException();
    }
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
  private synchronized void handleReply(Reply m, Address sender) {
    // Your code here...
    
  }

  private synchronized void handleViewReply(ViewReply m, Address sender) {
    // Your code here...
    currentView = m.view();
  }

  // Your code here...
  private synchronized void handleCSReply(CSReply m, Address sender) {
    if(m.viewNum() != currentView.viewNum() || m.result() == null) return;
    AMOResult res = (AMOResult)(m.result());
    if(request != null && res.sequenceNumber() != sequenceNum) {
      return;
    }
    this.result = res.result();
    notify();
  }

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  private synchronized void onClientTimer(ClientTimer t) {
    // Your code here...
    if(Objects.equal(request, t.request()) && result == null) {
      this.request = new CSRequest(currentView.viewNum(), this.request.command());
      send(request, currentView.primary());
      set(new ClientTimer(request), ClientTimer.CLIENT_RETRY_MILLIS);
    }
  }

  private synchronized void onPingTimer(PingTimer t) {
    // Your code here...
    send(new GetView(), viewServer);
    set(t, PingTimer.PING_MILLIS);
  }
}
