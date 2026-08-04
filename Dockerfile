FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /app

# 2C2G 调优:限制 Maven 堆,避免在小内存机器上构建时 OOM(配合 4G swap)
ENV MAVEN_OPTS="-Xmx768m -XX:MaxMetaspaceSize=256m"

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
COPY skills ./skills
COPY SOUL.md ./SOUL.md
# 跳过测试编译(-Dmaven.test.skip=true),构建更轻更快
RUN mvn -q -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre
WORKDIR /app

ENV TZ=Asia/Shanghai
ENV SERVER_PORT=18080
# 运行期堆上限:2C2G 与 MySQL/Redis/RabbitMQ 共存,必须限堆
ENV JAVA_OPTS="-Xmx512m -Xms256m"

COPY --from=builder /app/target/springclaw-java-0.0.1-SNAPSHOT.jar ./app.jar
COPY --from=builder /app/skills ./skills
COPY --from=builder /app/SOUL.md ./SOUL.md

EXPOSE 18080
# 用 JAVA_OPTS 让堆上限可在 compose 里覆盖
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
