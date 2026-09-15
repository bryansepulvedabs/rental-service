# Etapa 1: build del jar con Maven
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Dependencias primero: esta capa se cachea mientras el pom no cambie
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Código después: cambiar código ya no re-descarga dependencias
COPY src ./src
RUN mvn clean package -DskipTests

# Etapa 2: imagen final, solo con el jar
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8093
ENTRYPOINT ["java", "-jar", "app.jar"]