package dslabs.shardkv;

import dslabs.framework.Address;
import dslabs.framework.Timer;
import lombok.Data;

@Data
final class ClientTimer implements Timer {
  // Range, not a fixed interval, so concurrent clients don't fire in lockstep
  // and starve each other on the coordinator's lock.  Each call to
  // set(timer, MIN, MAX) picks a uniform random delay in [MIN, MAX].
  static final int CLIENT_MIN_RETRY_MILLIS = 75;
  static final int CLIENT_MAX_RETRY_MILLIS = 250;

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
  static final int MOVE_RETRY_MILLIS = 100;
}

@Data
final class PrepareTimer implements Timer {
  final Address[] destination;
  final PrepareTransactionRequest request;
  static final int PREPARE_RETRY_MILLIS = 100;
}

@Data
final class CommitTimer implements Timer {
  final Address[] destination;
  final CommitTransactionRequest request;
  static final int COMMIT_RETRY_MILLIS = 100;
}
