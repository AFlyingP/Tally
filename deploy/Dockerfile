FROM eclipse-temurin:21.0.12_8-jre-alpine
ARG SERVICE
RUN addgroup -S tally && adduser -S tally -G tally \
    && mkdir -p /var/lib/tally-auth && chown tally:tally /var/lib/tally-auth
WORKDIR /app
COPY ${SERVICE}/build/libs/${SERVICE}-1.0.0.jar /app/app.jar
USER tally
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
