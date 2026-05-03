package dslabs.shardkv;

import dslabs.framework.Command;
import dslabs.framework.Message;
import dslabs.framework.Result;
import dslabs.framework.Application;
import dslabs.framework.Address;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import dslabs.atmostonce.AMOApplication;
import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import dslabs.shardmaster.ShardMaster.ShardConfig;
import dslabs.kvstore.KVStore.KVStoreResult;

@Data
final class ShardStoreRequest implements Message {
  // Your code here...
  private final Integer configNum;
  private final String key;
  private final AMOCommand command;
  // Per-retry counter so each client retry gets a fresh paxos slot at the
  // server (avoiding the dedup-drop where a previously-rejected processX has
  // already consumed the slot for this (id, seqNum)).
  private final int attempt;
}

@Data
final class ShardStoreReply implements Message {
  // Your code here...
  private final Integer configNum;
  private final AMOResult result;
}

// Your code here...
@Data 
final class MoveRequest implements Message {
  private final Integer configNum;
  private final Integer shardId;
  private final AMOApplication<Application> app;
  private final Address[] senders;
}

@Data
final class MoveReply implements Message {
  private final Integer configNum;
  private final Integer shardId;
}

@Data
final class PrepareTransactionRequest implements Message {
  private final Integer configNum;
  private final AMOCommand command;
  private final Address[] senders;
  // Per-retry counter (incremented by coordinator's PrepareTimer) so each
  // re-send to the participant gets a fresh paxos slot.
  private final int attempt;
}

@Data
final class PrepareTransactionReply implements Message {
  private final Integer configNum;
  private final AMOCommand command;
  private final boolean result;
  private final Integer groupId;
  // Values of the txn's readSet keys that map to shards we own.  Populated
  // on a YES vote so the coord can assemble a full pre-image db across all
  // shards.  Needed for cross-shard writes (e.g., Swap), where each
  // participant's writes depend on values of keys it does *not* own.
  private final Map<String, String> readValues;
}

@Data
final class CommitTransactionRequest implements Message {
  private final Integer configNum;
  private final AMOCommand command;
  private final Address[] senders;
  private final boolean commit;
  // Per-retry counter (incremented by coordinator's CommitTimer) so each
  // re-send to the participant gets a fresh paxos slot.
  private final int attempt;
  // Aggregated readSet values from every participant (sent on commit=true).
  // Lets each participant build a *full* pre-image db before running the
  // txn locally, so cross-shard writes (Swap) compute correctly.  Null on
  // commit=false (abort path doesn't need values).
  private final Map<String, String> readValues;
}

@Data
final class CommitTransactionReply implements Message {
  private final Integer configNum;
  private final AMOCommand command;
  private final boolean committed;
  private final Integer groupId;
  private final KVStoreResult partialResult;
}

@Data
final class ShardStoreCommand implements Command {
  private final ShardStoreRequest request;
}

@Data
final class NewConfigCmd implements Command {
    private final ShardConfig config;
}

@Data
final class ShardMoveCmd implements Command {
    private final MoveRequest request;
}

@Data
final class ShardMoveAckCmd implements Command {
    private final MoveReply reply;
}

// ---- Transaction wrapper commands (for replicating txn events via Paxos) ----

@Data
final class TxnClientReqCmd implements Command {
    private final ShardStoreRequest request;
}

@Data
final class TxnPrepareReqCmd implements Command {
    private final PrepareTransactionRequest request;
}

@Data
final class TxnPrepareReplyCmd implements Command {
    private final PrepareTransactionReply reply;
}

@Data
final class TxnCommitReqCmd implements Command {
    private final CommitTransactionRequest request;
}

@Data
final class TxnCommitReplyCmd implements Command {
    private final CommitTransactionReply reply;
}
