package dslabs.primarybackup;

import dslabs.framework.Command;
import dslabs.framework.Message;
import dslabs.framework.Result;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import dslabs.atmostonce.AMOApplication;
import dslabs.framework.Application;

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
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Data
class PBCommandRequest extends Request {
  public PBCommandRequest(int viewNum, Command command) {
    super(viewNum, command);
  }
}

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Data
class PBCommandReply extends Reply {
  private final Command command; 
  public PBCommandReply(int viewNum, Result result, Command command) {
        super(viewNum, result);
        this.command = command;
    }
}

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Data
class CSRequest extends Request {
  public CSRequest(int viewNum, Command command) {
    super(viewNum, command);
  }
}

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Data
class CSReply extends Reply {
  public CSReply(int viewNum, Result result) {
        super(viewNum, result);
    }
}

@Data
class PBInitRequest implements Message {
  private final int viewNum;
  private final AMOApplication<Application> amoApp;
}

@Data
class PBInitReply implements Message {
  private final int viewNum;
  // You don't need a Result field here anymore. 
  // If the backup replies with this message, it implies success.

  private final Result result;
}