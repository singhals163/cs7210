package dslabs.paxos;

import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import dslabs.framework.Address;
import dslabs.framework.Client;
import dslabs.framework.Command;
import dslabs.framework.Node;
import dslabs.framework.Result;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public final class PaxosClient extends Node implements Client {
    private final Address[] servers;
    private int sequenceNum = 1;
    private AMOCommand pendingCommand;
    private AMOResult result;

    public PaxosClient(Address address, Address[] servers) {
        super(address);
        this.servers = servers;
    }

    @Override
    public synchronized void init() { }

    @Override
    public synchronized void sendCommand(Command operation) {
        pendingCommand = new AMOCommand(operation, address(), sequenceNum);
        result = null;
        broadcast(new PaxosRequest(pendingCommand));
        set(new ClientTimer(pendingCommand), ClientTimer.CLIENT_RETRY_MILLIS);
    }

    @Override
    public synchronized boolean hasResult() {
        return result != null;
    }

    @Override
    public synchronized Result getResult() throws InterruptedException {
        while (result == null) {
            wait();
        }
        return result.result();
    }

    private synchronized void handlePaxosReply(PaxosReply m, Address sender) {
        if (result != null) return; 

        if (!m.isLeader()) {
            return; // Ignore redirects, let the timer handle retries safely
        }
        
        AMOResult amoResult = m.result();
        if (amoResult != null && amoResult.sequenceNum() == sequenceNum) {
            result = amoResult;
            sequenceNum++;
            notifyAll();
        }
    }

    private synchronized void onClientTimer(ClientTimer t) {
        if (result == null && t.command().equals(pendingCommand)) {
            broadcast(new PaxosRequest(pendingCommand));
            set(t, ClientTimer.CLIENT_RETRY_MILLIS);
        }
    }

    private void broadcast(PaxosRequest request) {
        for (Address server : servers) {
            send(request, server);
        }
    }
}