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
