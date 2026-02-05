package dslabs.pingpong;

import dslabs.framework.Application;
import dslabs.framework.Command;
import dslabs.framework.Result;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;

@ToString
@EqualsAndHashCode
public class PingApplication implements Application {
  @Data
  public static final class Ping implements Command {
    @NonNull private final String value;
  }

  @Data
  public static final class Pong implements Result {
    @NonNull private final String value;
  }

  public PingApplication() {}

  // copy constructor
  public PingApplication(PingApplication application) {}
  
  @Override
  public Pong execute(Command command) {
    // TODO: fill this function as document
    // Check command validity; Generate result
    if (!(command instanceof Ping)) {
      throw new IllegalArgumentException();
    }

    Ping p = (Ping) command;

    return new Pong(p.value());
  }
}
