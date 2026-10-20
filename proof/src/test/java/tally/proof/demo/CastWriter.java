package tally.proof.demo;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** Writes an asciicast v2 recording of the demo output. */
public final class CastWriter implements AutoCloseable {

  private final BufferedWriter writer;
  private final long startedAt;

  public CastWriter(Path file, int cols, int rows) throws IOException {
    writer =
        Files.newBufferedWriter(
            file,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
    startedAt = System.nanoTime();
    writer.write(
        "{\"version\": 2, \"width\": "
            + cols
            + ", \"height\": "
            + rows
            + ", \"timestamp\": "
            + System.currentTimeMillis() / 1000
            + "}\n");
    writer.flush();
  }

  public void line(String text) throws IOException {
    double elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0;
    writer.write(
        "["
            + String.format(Locale.ROOT, "%.3f", elapsed)
            + ", \"o\", \""
            + escape(text + "\r\n")
            + "\"]\n");
    writer.flush();
  }

  @Override
  public void close() throws IOException {
    writer.close();
  }

  private static String escape(String value) {
    StringBuilder escaped = new StringBuilder();
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (character < 0x20) {
            escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
          } else {
            escaped.append(character);
          }
        }
      }
    }
    return escaped.toString();
  }
}
