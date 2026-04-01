package dslabs.atmostonce;

import dslabs.framework.Application;
import dslabs.framework.Command;
import dslabs.framework.Result;
import java.util.HashMap;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

@EqualsAndHashCode
@ToString
@RequiredArgsConstructor
public final class AMOApplication<T extends Application> implements Application {
    @Getter @NonNull private final T application;
    
    // Tracks the highest executed sequence number and its result per client
    private final Map<Address, AMOResult> executedCommands = new HashMap<>();

    @Override
    public AMOResult execute(Command command) {
        if (!(command instanceof AMOCommand)) {
            throw new IllegalArgumentException();
        }

        AMOCommand amoCommand = (AMOCommand) command;
        Address clientId = amoCommand.clientId();
        int seqNum = amoCommand.sequenceNum();

        if (alreadyExecuted(amoCommand)) {
            return executedCommands.get(clientId);
        }

        Result result = application.execute(amoCommand.command());
        AMOResult amoResult = new AMOResult(result, clientId, seqNum);
        executedCommands.put(clientId, amoResult);

        return amoResult;
    }

    public Result executeReadOnly(Command command) {
        if (!command.readOnly()) {
            throw new IllegalArgumentException();
        }
        if (command instanceof AMOCommand) {
            return execute(command);
        }
        return application.execute(command);
    }

    public boolean alreadyExecuted(AMOCommand amoCommand) {
        AMOResult lastResult = executedCommands.get(amoCommand.clientId());
        return lastResult != null && lastResult.sequenceNum() >= amoCommand.sequenceNum();
    }
}