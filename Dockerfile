# syntax=docker/dockerfile:1.7
# svc-pay-recurring-mandates container image.
# Build: docker build -t payment-recurring-mandates-service:dev .
# The image is built from source with the Gradle wrapper so CI and local
# builds produce the same artifact; tests run in ci/test, not here.

FROM eclipse-temurin:23-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY open-finance-domain open-finance-domain
COPY open-finance-application open-finance-application
COPY open-finance-infrastructure open-finance-infrastructure
COPY open-finance-bootstrap open-finance-bootstrap
RUN ./gradlew --no-daemon :open-finance-bootstrap:bootJar -x test \
 && java -Djarmode=tools -jar open-finance-bootstrap/build/libs/payment-recurring-mandates-service.jar \
      extract --layers --launcher --destination /workspace/extracted

FROM eclipse-temurin:23-jre AS runtime
RUN groupadd --system --gid 10001 mandates \
 && useradd --system --uid 10001 --gid mandates --no-create-home --shell /usr/sbin/nologin mandates
WORKDIR /app
# Layers ordered from least to most frequently changed for cache reuse.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001:10001
EXPOSE 8080 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
