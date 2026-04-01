package dslabs.atmostonce;

import dslabs.framework.Address;
import dslabs.framework.Command;
import lombok.Data;

@Data
public final class AMOCommand implements Command {
    private final Command command;
    private final Address clientId;
    private final int sequenceNum;
}