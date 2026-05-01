package dslabs.shardkv;

import dslabs.framework.Command;
import dslabs.framework.Message;
import dslabs.framework.Result;
import dslabs.framework.Application;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import dslabs.atmostonce.AMOApplication;
import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;

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
}

@Data
final class MoveReply implements Message {
  private final Integer configNum;
  private final Integer shardId;
}
