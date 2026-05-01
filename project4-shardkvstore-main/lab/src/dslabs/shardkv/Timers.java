package dslabs.shardkv;

import dslabs.framework.Address;
import dslabs.framework.Timer;
import lombok.Data;

@Data
final class ClientTimer implements Timer {
  static final int CLIENT_RETRY_MILLIS = 100;

  // Your code here...
  private final int sequenceNum;
}

// Your code here...
@Data
final class PingTimer implements Timer {
  static final int PING_RETRY_MILLIS = 100;
}

@Data
final class MoveTimer implements Timer {
  final Address[] destination;
  final MoveRequest request;
  static final int MOVE_RETRY_MILLIS = 25;
}
