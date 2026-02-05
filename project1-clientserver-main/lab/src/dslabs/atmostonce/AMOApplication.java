package dslabs.atmostonce;

import dslabs.framework.Address;
import dslabs.framework.Application;
import dslabs.framework.Command;
import dslabs.framework.Result;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import java.util.Map;
import java.util.HashMap;

@EqualsAndHashCode
@ToString
@RequiredArgsConstructor
public final class AMOApplication<T extends Application> implements Application {
  @Getter @NonNull private final T application;

  // Your code here...
  private Map<Address, AMOResult> results = new HashMap<>();

  @Override
  public AMOResult execute(Command command) {
    if (!(command instanceof AMOCommand)) {
      throw new IllegalArgumentException();
    }

    AMOCommand amoCommand = (AMOCommand) command;

    // Your code here...
    if(!alreadyExecuted(amoCommand)) {
      results.put(amoCommand.address(), new AMOResult(amoCommand.sequenceNumber(), application.execute(amoCommand.command())));
    }
    return results.get(amoCommand.address());
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
    // Your code here...
    AMOResult res = results.get(amoCommand.address());
    if(res != null && res.sequenceNumber() >= amoCommand.sequenceNumber()) {
      return true;
    }
    return false;
  }
}
