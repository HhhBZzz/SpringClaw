FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /app

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
COPY skills ./skills
COPY SOUL.md ./SOUL.md
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

ENV TZ=Asia/Shanghai
ENV SERVER_PORT=18080

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/target/springclaw-java-0.0.1-SNAPSHOT.jar ./app.jar
COPY --from=builder /app/skills ./skills
COPY --from=builder /app/SOUL.md ./SOUL.md

EXPOSE 18080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
