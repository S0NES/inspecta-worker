# inspecta-worker

Processamento pesado e decisoes complexas do **Inspecta** — plataforma de vistoria imobiliaria. Java 21 + Spring Boot 3 + AMQP.

## O que este projeto faz

Consome filas do RabbitMQ (publicadas via outbox pelo [inspecta-api](../inspecta-api)) e executa todo o trabalho pesado/assincrono do sistema, acessando o banco diretamente atraves do [inspecta-core](../inspecta-core) (sem REST interno):

| Fila | Responsabilidade |
|---|---|
| `q.assignment` | Motor de atribuicao de vistoria: elegibilidade, ranking, melhor horario + cron de retry |
| `q.pdf` | Geracao do laudo: JSON -> HTML -> OpenHTML2PDF -> armazenamento em Postgres |
| `q.image` | Processamento de imagem (Thumbnailator/webp): otimizada max 1200px q80 + thumbnail 300px q70 -> Postgres |
| `q.pleno` | Espelhamento/sincronizacao com a Pleno + cron poller |
| `q.signature` | Preparo do envio para assinatura (resolve laudo/signatários, exige PDF) -> `jobs.signature.create-document`. O IO com a Autentique é no inspecta-messenger. |
| `q.payment` | Criacao de cobranca no provider de pagamento (mock por padrao) + tratamento de webhook |

Tambem participa do outbox relay (`inspecta.outbox.relay.enabled`) junto com o inspecta-api, com SKIP LOCKED garantindo seguranca entre replicas.

## Stack

Java 21, Spring Boot 3.5, Spring AMQP (RabbitMQ), OpenHTML2PDF (geracao de PDF), Thumbnailator + webp-imageio (imagem), ShedLock (crons distribuidos). Pacote base: `br.com.vistoria.worker`; classe principal: `WorkerApplication`.

## Como rodar

### 1. Subir a infra local (na raiz do workspace `Inspecta/`)
```
docker compose up -d
```

### 2. Instalar a dependencia inspecta-core no repositorio Maven local
```
cd ../inspecta-core
mvn install
```

### 3. Rodar o inspecta-worker
```
cd ../inspecta-worker
mvn package
mvn spring-boot:run
```
Sobe com o profile `test` por padrao, na porta **8081**.

- Health check: http://localhost:8081/actuator/health

### PDF (fila `q.pdf`)
Nenhum passo de instalacao. O renderizador roda em processo (OpenHTML2PDF); ver
`docs/adr/ADR-001-pdf-engine.md` para o porque da troca do Playwright/Chromium.

### Docker (imagem de producao)
Build a partir da **raiz do workspace** (o contexto precisa enxergar o inspecta-core). A imagem final usa `eclipse-temurin:21-jre-jammy` (sem browser):
```
cd ..
docker build -f inspecta-worker/Dockerfile -t inspecta-worker .
```

### Variaveis de ambiente

#### Local (profile `test`, padrão)

O arquivo `.env` pode ser criado a partir de `.env.example` para conveniencia local. **Nunca é commitado:** `.env` está em `.gitignore`. Credenciais reais nunca entram no working tree.

#### Producao (profile `prod`)

**Infra obrigatoria (fail-fast, boot falha se nao setada):**

| Variavel | Descricao | Origem |
|---|---|---|
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | Postgres | Deploy secrets |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | RabbitMQ | Deploy secrets |
| `PUBLIC_BASE_URL` | domínio público do `inspecta-api` (ex.: `https://api.inspecta.viniciusscarabelli.tech`) — usado só para montar o link absoluto que o QR do laudo aponta (`LaudoHtmlBuilder`); precisa ser URL completa, um caminho relativo não é escaneável | Deploy secrets |

**Integracao obrigatoria (fail-fast, todas as variaveis requeridas):**

| Variavel | Descricao | Origem |
|---|---|---|
| `PAYMENT_PROVIDER` | provider de pagamento ativo (nao tem default) | Deploy secrets |
| `ABACATEPAY_API_KEY`, `ABACATEPAY_WEBHOOK_PUBLIC_KEY` | credenciais AbacatePay (se provider=abacatepay) | Deploy secrets |
| `PLENO_MODE`, `PLENO_BASE_URL`, `PLENO_CLIENT_ID`, `PLENO_CLIENT_SECRET` | integracao Pleno (se mode=http) | Deploy secrets |
| `SIGNATURE_REMINDER_ENABLED` / `_CRON` / `_WINDOW_DAYS` / `_SPACING_HOURS` | cron de lembrete de assinatura pendente (opcional, tem default) | — |

**Onde vem as secrets em prod:** Variáveis de ambiente são configuradas no sistema de deploy (Kubernetes secrets, Cloud provider env vars, etc.), nunca em `.env` — o arquivo `.env` é apenas para desenvolvimento local. Ver `backend-specs/arquitetura.md` § Build local para maiores detalhes e T7.3 para o fluxo completo de deploy.

Ver `backend-specs/eventos.md` para os contratos de fila.

## Licenca

Software proprietario. Uso comercial sujeito a licenca — ver [LICENSE](./LICENSE).
