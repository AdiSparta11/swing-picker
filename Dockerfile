# Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

# Run: small heap so it fits Render's free instance
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/swing-picker-*.jar app.jar
ENV JAVA_OPTS="-Xmx300m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
