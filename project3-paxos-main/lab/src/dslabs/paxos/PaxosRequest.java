package dslabs.paxos;

import dslabs.framework.Command;
import dslabs.framework.Message;
import lombok.Data;

@Data
public final class PaxosRequest implements Message {
    private final Command command;
}