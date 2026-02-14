package dslabs.primarybackup;

import dslabs.framework.Timer;
import lombok.Data;

@Data
final class PingCheckTimer implements Timer {
  static final int PING_CHECK_MILLIS = 100;
}

@Data
final class PingTimer implements Timer {
  static final int PING_MILLIS = 25;
}

@Data
final class ClientTimer implements Timer {
  static final int CLIENT_RETRY_MILLIS = 100;

  // Your code here...
  private final Request request;
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
