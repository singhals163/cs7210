package dslabs.kvstore;

import dslabs.framework.Application;
import dslabs.framework.Command;
import dslabs.framework.Result;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;
import java.util.HashMap;
import java.util.Map;


@ToString
@EqualsAndHashCode
public class KVStore implements Application {

  public interface KVStoreCommand extends Command {}

  public interface SingleKeyCommand extends KVStoreCommand {
    String key();
  }

  @Data
  public static final class Get implements SingleKeyCommand {
    @NonNull private final String key;

    @Override
    public boolean readOnly() {
      return true;
    }
  }

  @Data
  public static final class Put implements SingleKeyCommand {
    @NonNull private final String key, value;
  }

  @Data
  public static final class Append implements SingleKeyCommand {
    @NonNull private final String key, value;
  }

  public interface KVStoreResult extends Result {}

  @Data
  public static final class GetResult implements KVStoreResult {
    @NonNull private final String value;
  }

  @Data
  public static final class KeyNotFound implements KVStoreResult {}

  @Data
  public static final class PutOk implements KVStoreResult {}

  @Data
  public static final class AppendResult implements KVStoreResult {
    @NonNull private final String value;
  }

  // Your code here...
  @Data
  public static final class Init implements KVStoreCommand {
    @NonNull private final Map<String, String> store;
  }

  @Data
  public static final class InitOK implements KVStoreResult {}

  private Map<String, String> store = new HashMap<>();


  @Override
  public KVStoreResult execute(Command command) {
    if (command instanceof Get) {
      Get g = (Get) command;
      // Your code here...
      if(store.containsKey(g.key())) {
        return new GetResult(store.get(g.key()));
      } else {
        return new KeyNotFound();
      }
    }

    if (command instanceof Put) {
      Put p = (Put) command;
      // Your code here...
      store.put(p.key(), p.value());
      return new PutOk();
    }

    if (command instanceof Append) {
      Append a = (Append) command;
      // Your code here...
      String value = store.getOrDefault(a.key(), "");
      value += a.value();
      store.put(a.key(), value);
      return new AppendResult(value);
    }

    if (command instanceof Init) {
      Init i = (Init) command;
      store = i.store;
      return new InitOK();
    }

    throw new IllegalArgumentException();
  }

  public Init generateInitCommand() {
    return new Init(store);
  }
}
