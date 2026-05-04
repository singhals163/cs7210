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

// Used by maybeTriggerPendingConfigChange to *defer* the newConfig paxos
// propose to the next event-loop iteration.  Synchronously invoking
// handleMessage(PaxosRequest, paxosAddress) from inside a paxos decide
// handler (e.g. processCommitTransactionRequest) re-enters paxos's drain
// loop while its `slotOut` is still pointing at the slot that's currently
// being drained, causing infinite recursion / StackOverflow.  A 1ms timer
// breaks the synchronous chain.
@Data
final class ConfigProposeTimer implements Timer {
  static final int CONFIG_PROPOSE_DELAY_MILLIS = 1;
}
