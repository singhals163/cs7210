package dslabs.clientserver;

import dslabs.framework.Message;
import lombok.Data;
import dslabs.framework.Command;
import dslabs.framework.Result;


@Data
class Request implements Message {
  // Your code here...
  private final Command command;
}

@Data
class Reply implements Message {
  // Your code here...
  private final Result result;
}
