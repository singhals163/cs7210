package dslabs.paxos;

import dslabs.framework.Command;
import dslabs.framework.Message;
import lombok.Data;

@Data
public final class PaxosRequest implements Message {
    // Carries the client's operation. In this project, this will 
    // always be an instance of AMOCommand.
    private final Command command;
}