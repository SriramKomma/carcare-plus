# CarCare+ on Render (or any host that runs a Docker image).
# Plain Java 21, the two approved libraries (jsoup, Gson), no web framework.

# ---- 1. build: compile, copy the libraries, build the search data
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
COPY config config
COPY fixtures fixtures
COPY scripts scripts
RUN mvn -q -B -DskipTests compile dependency:copy-dependencies -DincludeScope=runtime
RUN sh scripts/build-data.sh

# ---- 2. run: only the Java runtime, the classes, the libraries, config and data
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/classes target/classes
COPY --from=build /app/target/dependency target/dependency
COPY --from=build /app/config config
COPY --from=build /app/data data
# Hosted mode: listen on every address; the live demo needs CARCARE_DEMO_KEY (set on Render)
ENV CARCARE_HOSTED=true
EXPOSE 8080
CMD ["sh", "-c", "exec java -Xmx320m -cp 'target/classes:target/dependency/*' ca.carcareplus.app.Main web"]
