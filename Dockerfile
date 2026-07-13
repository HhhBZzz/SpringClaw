FROM maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc AS builder
WORKDIR /app

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
COPY skills ./skills
COPY SOUL.md ./SOUL.md
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre@sha256:1824944ef1bd572d1ff0952afeb2fec7931d77c972c4fbc4dfcdf89f758fb490
WORKDIR /app

ENV TZ=Asia/Shanghai
ENV SERVER_PORT=18080

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/target/springclaw-0.0.1-SNAPSHOT.jar ./app.jar
COPY --from=builder /app/skills ./skills
COPY --from=builder /app/SOUL.md ./SOUL.md

EXPOSE 18080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
