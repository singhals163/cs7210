package dslabs.primarybackup;

import dslabs.framework.Command;
import dslabs.framework.Message;
import lombok.Data;

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
  public PBCommandReply(int viewNum, Result result) {
        super(viewNum, result);
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
  Address sender;
  public CSRequest(int viewNum, Command command, Address a) {
    super(viewNum, command);
    sender = a;
  }
}

@EqualsAndHashCode(callSuper = true)
@Data
class CSReply extends Reply {
  public CSReply(int viewNum, Result result) {
        super(viewNum, result);
    }
}