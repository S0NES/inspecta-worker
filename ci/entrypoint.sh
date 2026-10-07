#!/bin/bash
# Build-runner do inspecta-worker (chamado via docker compose pelo Jenkinsfile em ci/Jenkinsfile).
# Faz só build + testes; publish (docker push) e deploy (webhook) são estágios
# separados no Jenkinsfile, rodando direto no agente Jenkins -- não aqui.
set -euo pipefail

REGISTRY="${REGISTRY:-registry.inspecta.viniciusscarabelli.tech}"
IMAGE_NAME="${IMAGE_NAME:-${REGISTRY}/inspecta-worker}"
FINAL_TAG="${IMAGE_NAME}:${IMAGE_TAG:-latest}"

echo "== [1/2] mvn verify (testes existentes, incl. Testcontainers — docker.sock disponível aqui) =="
mvn -B -s settings.xml -f pom.xml verify

echo "== [2/2] docker build (tag final; publish/deploy ficam a cargo do Jenkinsfile) =="
# Cache-bust da layer `mvn package` do Dockerfile: o inspecta-core e SNAPSHOT
# (mutavel), e sem isto o Docker serve o jar do build anterior quando pom.xml/src
# nao mudaram. O <lastUpdated> do maven-metadata do SNAPSHOT muda a cada deploy do
# inspecta-core; se nao resolver (offline / versao fixa), cai pro epoch e builda limpo.
GU_VER=$(sed -n '/<artifactId>inspecta-core<\/artifactId>/,/<\/dependency>/ s:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1)
GU_TS=$(curl -sf "https://maven.inspecta.viniciusscarabelli.tech/snapshots/com/inspecta/inspecta-core/${GU_VER}/maven-metadata.xml" \
  | sed -n 's:.*<lastUpdated>\(.*\)</lastUpdated>.*:\1:p' | head -1 || true)
docker build --build-arg "GU_SNAPSHOT_TS=${GU_TS:-$(date +%s)}" -t "$FINAL_TAG" -f Dockerfile .
