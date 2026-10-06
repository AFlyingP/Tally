package tally.ledger.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

public final class CanonicalJson {

  private static final DateTimeFormatter INSTANT_OUT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .disable(SerializationFeature.INDENT_OUTPUT)
          .disable(EnumFeature.WRITE_ENUMS_USING_TO_STRING)
          .disable(EnumFeature.WRITE_ENUMS_USING_INDEX)
          .changeDefaultPropertyInclusion(
              incl ->
                  incl.withValueInclusion(JsonInclude.Include.ALWAYS)
                      .withContentInclusion(JsonInclude.Include.ALWAYS))
          .addModule(
              new SimpleModule("canonical-time").addSerializer(Instant.class, new InstantWriter()))
          .build();

  private CanonicalJson() {}

  /** Serializes a request record with stable property names, ordering, and timestamps. */
  public static String of(Object requestRecord) {
    return MAPPER.writeValueAsString(requestRecord);
  }

  public static String empty() {
    return "";
  }

  private static final class InstantWriter extends StdSerializer<Instant> {

    InstantWriter() {
      super(Instant.class);
    }

    @Override
    public void serialize(Instant value, JsonGenerator gen, SerializationContext context) {
      gen.writeString(INSTANT_OUT.format(value));
    }
  }
}
