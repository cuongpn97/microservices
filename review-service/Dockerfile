# Image runtime cho review-service. Jar được build trước bằng: ./mvnw -pl review-service -am package -DskipTests
# (build ngoài Docker để tận dụng cache Maven local và giữ image nhỏ, chỉ chứa JRE + jar).
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY target/review-service-1.0.0-SNAPSHOT.jar /app/app.jar
USER app

EXPOSE 8088
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=12   CMD wget -qO- http://localhost:8088/actuator/health/readiness > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
