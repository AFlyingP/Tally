package tally.platform.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.databind.type.LogicalType;

@Configuration
public class JsonConfig {

  private static final DateTimeFormatter INSTANT_OUT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
  private static final Pattern INSTANT_IN =
      Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?Z");

  @Bean
  JsonMapperBuilderCustomizer tallyJson() {
    return builder ->
        builder
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .withCoercionConfig(
                LogicalType.Integer,
                config ->
                    config
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail))
            .changeDefaultPropertyInclusion(
                incl ->
                    incl.withValueInclusion(JsonInclude.Include.ALWAYS)
                        .withContentInclusion(JsonInclude.Include.ALWAYS))
            .addModule(
                new SimpleModule("tally-time")
                    .addSerializer(Instant.class, new InstantWriter())
                    .addDeserializer(Instant.class, new InstantReader()));
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

  private static final class InstantReader extends StdDeserializer<Instant> {

    InstantReader() {
      super(Instant.class);
    }

    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context) {
      if (!parser.hasToken(JsonToken.VALUE_STRING)) {
        return (Instant) context.handleUnexpectedToken(Instant.class, parser);
      }
      String text = parser.getString();
      if (!INSTANT_IN.matcher(text).matches()) {
        return (Instant)
            context.handleWeirdStringValue(
                Instant.class, text, "expected a UTC timestamp ending in Z");
      }
      return Instant.parse(text);
    }
  }
}
