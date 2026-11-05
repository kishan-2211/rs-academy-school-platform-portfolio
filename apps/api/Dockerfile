FROM maven:3.9.11-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace
COPY apps/api/pom.xml apps/api/pom.xml
RUN mvn -B -ntp -f apps/api/pom.xml dependency:go-offline
COPY apps/api/src apps/api/src
RUN mvn -B -ntp -f apps/api/pom.xml verify -DskipTests \
    && java -Djarmode=tools -jar apps/api/target/educore-api-0.1.0-SNAPSHOT.jar extract --layers --destination apps/api/target/extracted

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN apk add --no-cache --upgrade \
        libcrypto3=3.5.9-r0 libssl3=3.5.9-r0 openssl=3.5.9-r0 \
        zlib=1.3.2-r1 libpng=1.6.59-r0 libexpat=2.10.0-r0 \
    && apk del --no-network gnupg \
    && addgroup -S -g 10001 educore \
    && adduser -S -u 10001 -G educore educore
COPY --from=builder --chown=educore:educore /workspace/apps/api/target/extracted/dependencies/ ./
COPY --from=builder --chown=educore:educore /workspace/apps/api/target/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=educore:educore /workspace/apps/api/target/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=educore:educore /workspace/apps/api/target/extracted/application/ ./
USER educore
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "educore-api-0.1.0-SNAPSHOT.jar"]
