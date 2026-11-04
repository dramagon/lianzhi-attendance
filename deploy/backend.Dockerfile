FROM eclipse-temurin:17-jdk AS build
WORKDIR /build
COPY backend/ ./
RUN chmod +x mvnw && ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre
RUN groupadd --system app && useradd --system --gid app --home /app app && mkdir -p /app/data/avatars && chown -R app:app /app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/lianzhi-backend-0.1.0.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
