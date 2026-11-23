FROM node:24-bookworm-slim AS frontend
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /app
COPY backend/pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /app/dist/ ./src/main/resources/static/
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /app/target/leetgrind.jar ./
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/leetgrind.jar"]
