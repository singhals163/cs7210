package dslabs.clientserver;

import com.google.common.base.Objects;
import dslabs.framework.Address;
import dslabs.framework.Client;
import dslabs.framework.Command;
import dslabs.framework.Node;
import dslabs.framework.Result;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import dslabs.kvstore.KVStore.Get;
import dslabs.kvstore.KVStore.Put;
import dslabs.kvstore.KVStore.Append;
import dslabs.kvstore.KVStore.GetResult;
import dslabs.kvstore.KVStore.PutOk ;
import dslabs.kvstore.KVStore.AppendResult;
import dslabs.kvstore.KVStore.KeyNotFound;
import dslabs.kvstore.KVStore;
import dslabs.clientserver.Request;

/**
 * Simple client that sends requests to a single server and returns responses.
 *
 * <p>See the documentation of {@link Client} and {@link Node} for important implementation notes.
 */
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
class SimpleClient extends Node implements Client {
  private final Address serverAddress;

  // Your code here...
  private Request request;
  private Result result;
  private int sequenceNum = 0;

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  public SimpleClient(Address address, Address serverAddress) {
    super(address);
    this.serverAddress = serverAddress;
  }

  @Override
  public synchronized void init() {
    // No initialization necessary
  }

  /* -----------------------------------------------------------------------------------------------
   *  Client Methods
   * ---------------------------------------------------------------------------------------------*/
  @Override
  public synchronized void sendCommand(Command command) {
    // Your code here...
    if(command instanceof Get) {
      request = new Request((Get)command, sequenceNum+1);
    } else if(command instanceof Put) {
      request = new Request((Put)command, sequenceNum+1);
    } else if(command instanceof Append) {
      request = new Request((Append)command, sequenceNum+1);
    } else {
      throw new IllegalArgumentException();
    }
    sequenceNum++;
    result = null;
    send(request, serverAddress);
    set(new ClientTimer(request), ClientTimer.CLIENT_RETRY_MILLIS);
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
    if(request != null && m.sequenceNum() != request.sequenceNum()) {
      return;
    }
    if(m.result() instanceof GetResult) {
      result = (GetResult)(m.result());
    } else if(m.result() instanceof PutOk) {
      result = (PutOk)(m.result());
    } else if(m.result() instanceof AppendResult) {
      result = (AppendResult)(m.result());
    } else if(m.result() instanceof KeyNotFound) {
      result = (KeyNotFound)(m.result());
    } else {
      return;
    }
    notify();
  }

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  private synchronized void onClientTimer(ClientTimer t) {
    // Your code here...
    if(Objects.equal(request, t.request()) && result == null) {
      send(request, serverAddress);
      set(t, ClientTimer.CLIENT_RETRY_MILLIS);
    }
  }
}
