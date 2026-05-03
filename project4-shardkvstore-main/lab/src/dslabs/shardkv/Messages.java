package dslabs.shardkv;

import dslabs.framework.Command;
import dslabs.framework.Message;
import dslabs.framework.Result;
import dslabs.framework.Application;
import dslabs.framework.Address;
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
}

@Data
final class PrepareTransactionReply implements Message {
  private final Integer configNum;
  private final AMOCommand command;
  private final boolean result;
  private final Integer groupId;
}

@Data
final class CommitTransactionRequest implements Message {
  private final Integer configNum;
  private final AMOCommand command;
  private final Address[] senders;
  private final boolean commit;
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
