# Build context = a própria pasta do inspecta-worker. inspecta-core é resolvido via Maven
# a partir do Reposilite (repositório interno no Dokploy, ver pom.xml <repositories>)
# — não precisa mais clonar o repo inspecta-core lado a lado.
#   docker build -f Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /ws
COPY settings.xml settings.xml
COPY pom.xml pom.xml
COPY src src
# inspecta-core e 1.3.0-SNAPSHOT (mutavel). Sem este ARG o Docker reusa a layer do
# `mvn package` sempre que pom.xml/src nao mudam e assa o jar do inspecta-core do
# build anterior, mesmo com updatePolicy=always (a layer nao re-roda, entao o
# download nem acontece). ci/entrypoint.sh passa o <lastUpdated> do maven-metadata
# do SNAPSHOT; muda a cada deploy do inspecta-core. Default `dev` = build local ok.
ARG GU_SNAPSHOT_TS=dev
RUN echo "inspecta-core snapshot: ${GU_SNAPSHOT_TS}" \
 && mvn -q -s settings.xml -f pom.xml package -DskipTests

# Runtime sem browser: o PDF agora e renderizado em processo pelo OpenHTML2PDF
# (ver docs/adr/ADR-001-pdf-engine.md). Saiu a imagem mcr.microsoft.com/playwright/java,
# que carregava um Chromium inteiro (~400 MB) so para imprimir HTML estatico.
#
# Base glibc (jammy), NAO alpine/musl como inspecta-api e inspecta-messenger, por dois motivos
# especificos deste servico:
#   - org.sejda.imageio:webp-imageio embarca binarios nativos compilados contra glibc;
#   - fontconfig/freetype sao necessarios para o Java2D usado no pipeline de imagem
#     (thumbnailator) e para as metricas de fonte do renderizador de PDF.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update \
 && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core curl \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /ws/target/*.jar app.jar
EXPOSE 8081
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
  CMD curl -fsS http://localhost:8081/actuator/health | grep -q UP || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","app.jar"]
