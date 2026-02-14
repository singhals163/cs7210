package dslabs.primarybackup;

import dslabs.framework.Timer;
import lombok.Data;
import dslabs.framework.Command;

@Data
final class PingCheckTimer implements Timer {
  static final int PING_CHECK_MILLIS = 100;
}

@Data
final class PingTimer implements Timer {
  static final int PING_MILLIS = 50;
}

@Data
final class ClientTimer implements Timer {
  static final int CLIENT_RETRY_MILLIS = 25;

  // Your code here...
  private final Command command;
}

// Your code here...
@Data
final class InitTimer implements Timer {
  static final int INIT_MILLIS = 25;
  final int viewNum;
  final Request request;
}

@Data
final class PBCommandTimer implements Timer {
  static final int PB_COMMAND_MILLIS = 25;
  final int viewNum;
}
