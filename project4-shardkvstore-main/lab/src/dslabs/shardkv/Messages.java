package dslabs.shardkv;

import dslabs.framework.Command;
import dslabs.framework.Message;
import dslabs.framework.Result;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
final class ShardStoreRequest implements Message {
  // Your code here...
  private final Command command;
}

@Data
final class ShardStoreReply implements Message {
  // Your code here...
  private final Result result;
}

// Your code here...
@Data
final class ShradMove implements Command {

}

@Data
final class ShardMoveAck implements Command {

}

@Data
final class NewConfig implements Command {

}
