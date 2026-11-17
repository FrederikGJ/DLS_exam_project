# shop-service

Butikker, navigationsnetværk og ruteberegning (Dijkstra) for lufthavnen – plus `askRoute`, hvor en passager kan
spørge i fri tekst (dansk eller engelsk) og få en rute via den rigtige butik.

GraphQL: `http://localhost:8085/api/shops/graphql` (GraphiQL: `/graphiql?path=/api/shops/graphql` i dev).
Arkitektur og designvalg: [docs/architecture.md](../docs/architecture.md) (Flow D og Flow E).

## askRoute (AI)

```graphql
query {
  askRoute(question: "Hvor kan jeg købe kaffe på vej til gate B12?", fromNodeId: 24) {
    aiUsed fallbackReason
    shop { name terminal }
    destination { name }
    route { totalDistanceM estimatedMinutes steps { instruction } }
  }
}
```

1. `AskRouteService` slår startnoden op (`NOT_FOUND` hvis den ikke findes) og henter butikker og noder.
2. `RoutePrompt` bygger prompten: butikker og destinationer (gates, security, indgange) som **lukkede lister af
   navne**, og et JSON schema hvor `shop` og `destination` er `enum`s af netop de navne (+ `NONE`).
3. `OllamaClient` kalder `POST {OLLAMA_URL}/api/chat` med schemaet som `format` (samme `RestClient`-mønster som
   booking-service' `FlightClient`). Fejl/timeout → `UPSTREAM_UNAVAILABLE`.
4. `RoutePrompt.parse` validerer svaret og mapper navnene tilbage til entiteter. Ukendt navn eller ugyldig JSON →
   fallback. Modellen foreslår – koden bestemmer, så en hallucineret butik kan aldrig give en rute.
5. `KeywordMatcher` er fallbacken (søgeord på dansk og engelsk → kategori/navn/beskrivelse).
6. `RouteService.routeVia` beregner start → butik → destination med den eksisterende Dijkstra.

Hvilken vej der svarede, logges (`askRoute answered by AI …` / `askRoute answered by fallback (<grund>) …`), og
`OllamaClient` logger hvor lang tid LLM-kaldet tog.

### Konfiguration

| Variabel         | Default                   | Betydning                                                    |
|------------------|---------------------------|--------------------------------------------------------------|
| `AI_ENABLED`     | `true`                    | `false` → altid fallback, Ollama kaldes aldrig                |
| `OLLAMA_URL`     | `http://localhost:11434`  | compose/k8s: `http://ollama:11434`                            |
| `OLLAMA_MODEL`   | `qwen2.5:3b`              | ~2,5 GB RAM; `qwen2.5:1.5b` (~1,5 GB RAM) ved lidt RAM, men svagere valg |
| `OLLAMA_TIMEOUT` | `30s`                     | read-timeout; første spørgsmål efter model-load på CPU er langsomt |

Ollama er valgfri: `docker compose --profile ai up` lokalt, `kubectl apply -k k8s/ai` i Kubernetes. Uden Ollama
starter shop-service normalt, og `askRoute` svarer med fallbacken.

Skift model lokalt: sæt `MODEL` på `ollama` og `OLLAMA_MODEL` på `shop-service` i `docker-compose.yml` til samme
værdi og kør `docker compose --profile ai up -d`. Modellen hentes første gang ind i volumen `ollama-data`.

### Tests

```bash
mvn verify      # kræver Docker (Testcontainers)
```

* `ai/KeywordMatcherTest` – fallbacken: dansk/engelsk, navn, kategori, terminal, destination.
* `ai/AskRouteServiceTest` – beslutningslogikken med en mocket `OllamaClient`.
* `AskRouteIntegrationTest` – hele kæden over HTTP med WireMock som Ollama (ingen rigtig model i CI): happy path,
  ugyldig JSON, timeout, Ollama nede (500), opfundet butik, ukendt `fromNodeId`.
