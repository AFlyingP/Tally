package tally.proof.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.jqwik.api.lifecycle.AroundPropertyHook;
import net.jqwik.api.lifecycle.PropertyExecutionResult;
import net.jqwik.api.lifecycle.PropertyExecutor;
import net.jqwik.api.lifecycle.PropertyLifecycleContext;
import tally.proof.gen.Op;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Records the shrunk sequence after a property fails. */
public final class FailureFile implements AroundPropertyHook {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Override
  public PropertyExecutionResult aroundProperty(
      PropertyLifecycleContext context, PropertyExecutor property) throws Throwable {
    PropertyExecutionResult result = property.execute();
    if (result.status() == PropertyExecutionResult.Status.FAILED
        && result.falsifiedParameters().isPresent()) {
      List<?> sequence = (List<?>) result.falsifiedParameters().orElseThrow().getFirst();
      List<Op> ops = sequence.stream().map(Op.class::cast).toList();
      String name = context.targetMethod().getName();
      String strategy = name.substring(0, name.length() - "MatchesModel".length());
      write(name, result.seed().orElseThrow(), strategy, ops);
    }
    return result;
  }

  public static Path write(String property, String seed, String strategy, List<Op> ops)
      throws IOException {
    Path directory = Path.of(System.getProperty("proof.root", ".."), "proof/build/failures");
    Files.createDirectories(directory);
    Path file = directory.resolve(property + "-" + seed + ".json");
    ObjectNode root = JSON.createObjectNode();
    root.put("property", property);
    root.put("seed", seed);
    root.put("strategy", strategy);
    ArrayNode sequence = root.putArray("ops");
    for (Op op : ops) {
      ObjectNode node = JSON.valueToTree(op);
      node.put("type", op.getClass().getSimpleName());
      sequence.add(node);
    }
    Files.writeString(file, JSON.writeValueAsString(root));
    return file;
  }
}
