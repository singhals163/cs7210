package dslabs.primarybackup;

import dslabs.framework.Command;
import dslabs.framework.Message;
import dslabs.framework.Result;
import lombok.Data;
import lombok.EqualsAndHashCode;

/* -----------------------------------------------------------------------------------------------
 *  ViewServer Messages
 * ---------------------------------------------------------------------------------------------*/
@Data
class Ping implements Message {
  private final int viewNum;
}

@Data
class GetView implements Message {}

@Data
class ViewReply implements Message {
  private final View view;
}

/* -----------------------------------------------------------------------------------------------
 *  Primary-Backup Messages
 * ---------------------------------------------------------------------------------------------*/
@Data
class Request implements Message {
  // Your code here...
  private final int viewNum;
  private final Command command;
}

@Data
class Reply implements Message {
  // Your code here...
  private final int viewNum;
  private final Result result;
}

// Your code here...

@EqualsAndHashCode(callSuper = true)
@Data
class PBCommandRequest extends Request {
  public PBCommandRequest(int viewNum, Command command) {
    super(viewNum, command);
  }
}

@EqualsAndHashCode(callSuper = true)
@Data
class PBCommandReply extends Reply {
  private final Command command; 
  public PBCommandReply(int viewNum, Result result, Command command) {
        super(viewNum, result);
        this.command = command;
    }
}

@EqualsAndHashCode(callSuper = true)
@Data
class PBInitRequest extends Request {
  public PBInitRequest(int viewNum, Command command) {
    super(viewNum, command);
  }
}

@EqualsAndHashCode(callSuper = true)
@Data
class PBInitReply extends Reply {
  public PBInitReply(int viewNum, Result result) {
        super(viewNum, result);
    }
}

@EqualsAndHashCode(callSuper = true)
@Data
class CSRequest extends Request {
  public CSRequest(int viewNum, Command command) {
    super(viewNum, command);
  }
}

@EqualsAndHashCode(callSuper = true)
@Data
class CSReply extends Reply {
  public CSReply(int viewNum, Result result) {
        super(viewNum, result);
    }
}