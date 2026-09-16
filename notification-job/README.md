# notification-job

Sender (simulerede) e-mails til passagerer, når der sker noget med deres booking. Et almindeligt Java 21-program
**uden Spring**, der kører til ende: det forbinder til RabbitMQ, tømmer køen `notifications`, skriver én dansk mail
pr. event i loggen og stopper med exit code 0. Der sendes ingen rigtig mail (ingen SMTP) – mailen logges.

Fordi programmet stopper af sig selv, når køen er tom, kan det køres som en *serverless*-funktion: i Kubernetes
startes det af en KEDA ScaledJob, der skalerer på kølængden – nul pods når køen er tom, flere parallelle jobs når
der hober sig beskeder op (dev plan DP-21). Lokalt køres det med docker compose eller `docker run`.

| | |
|---|---|
| Type | run-to-completion job (ingen port, ingen health-endpoint) |
| Kø ind | `notifications` ← `airport.events` med binding `booking.#` |
| Dead-letter | `airport.events.dlx` → `notification-job.dlq` (routing key `notification-job`) |
| Events med mail | `booking.created`, `booking.confirmed`, `booking.cancelled`, `booking.checkedin` |
| Image | `airport/notification-job:local` (`eclipse-temurin:21-jre-alpine`, non-root) |

## Hvad jobbet gør

```
declare topologi (idempotent) -> basicQos(1) -> basicConsume(autoAck=false)
indtil MAX_MESSAGES beskeder er behandlet, eller der ikke er kommet en besked i IDLE_TIMEOUT_MS:
  booking-event med skabelon      -> render mail, log den  -> ack
  eventtype uden skabelon         -> log "Skipping"         -> ack    (fx et fremtidigt booking.x.v2)
  ugyldig JSON / manglende felter -> nack(requeue=false)    -> notification-job.dlq
stop consumer, luk forbindelsen -> exit 0
```

- **Topologi.** Jobbet erklærer exchange, kø, DLX, DLQ og bindings ved hver start. Det er idempotent, fordi
  RabbitMQ accepterer en gen-erklæring med *præcis* samme argumenter. `booking-service` erklærer den samme kø
  (`config/RabbitConfig`), så booking-events gemmes fra det øjeblik servicen starter – også før jobbet har kørt
  første gang (et event til en topic exchange uden matchende kø bliver ellers smidt væk). Ændres argumenterne ét
  sted, skal det ske begge steder; ellers svarer RabbitMQ `PRECONDITION_FAILED`, og den der erklærer sidst fejler.
- **Prefetch 1 + manuel ack.** Brokeren udleverer én besked ad gangen, og den kvitteres først, når mailen er
  renderet og logget. Når `MAX_MESSAGES` er nået, annulleres consumeren *før* sidste ack, så der ikke udleveres en
  ekstra besked, der bare skulle lægges tilbage.
- **Ugyldige beskeder** (ikke JSON, mangler `eventId`/`eventType`, `payload.bookingReference`, `flightNumber`,
  `departureTime` eller `passenger.email`) kan ikke blive gyldige ved at prøve igen. De afvises uden requeue og
  lander i `notification-job.dlq` med RabbitMQ's `x-death`-header (reason `rejected`). Det samme gælder, hvis en
  skabelon fejler med en uventet exception – ellers ville én "giftig" besked få hvert eneste job til at crashe.
- **Ukendte felter ignoreres.** JSON læses som træ (`path(...)`), så en producent kan tilføje felter uden at jobbet
  går i stykker. Valgfrie felter (fornavn, sæde, pris, årsag) udelades bare af mailen, hvis de mangler.
- **Tider og beløb** vises i dansk tid (Europe/Copenhagen) og dansk talformat: `11. september 2026 kl. 16:00`,
  `1.299,00 DKK`.

### Leveringsgaranti: at-least-once

En besked kvitteres først, når mailen er afleveret. Dør jobbet imellem (pod slået ihjel, forbindelsen tabt), lægger
RabbitMQ beskeden tilbage i køen, og næste kørsel renderer mailen igen. **Dubletter er altså mulige.** En rigtig
mailudbyder (SMTP-relay, SendGrid, SES …) skulle have `eventId` med som idempotency-nøgle, så den samme mail ikke
sendes to gange – `eventId` følger allerede med i `Mail`-recorden. Fejler selve afsendelsen (`MailSender` kaster),
stopper jobbet uden ack med exit code 1, og beskeden prøves igen ved næste kørsel.

## Konfiguration (miljøvariabler)

| Variabel | Default | Betydning |
|---|---|---|
| `RABBITMQ_HOST` | `localhost` | RabbitMQ-host (`rabbitmq` i compose) |
| `RABBITMQ_PORT` | `5672` | AMQP-port |
| `RABBITMQ_USERNAME` | `airport` | bruger |
| `RABBITMQ_PASSWORD` | `airport` | password (logges aldrig) |
| `NOTIFICATION_QUEUE` | `notifications` | køen der tømmes |
| `MAX_MESSAGES` | `100` | stop efter så mange beskeder – begrænser hvor længe ét job kører |
| `IDLE_TIMEOUT_MS` | `5000` | stop når der ikke er kommet en besked i så lang tid (køen er tom) |

Defaults passer til compose-stakken og er **kun til lokal udvikling**; i Kubernetes kommer brugernavn og password
fra et Secret. Mere log fra amqp-client: `JAVA_TOOL_OPTIONS=-Dorg.slf4j.simpleLogger.defaultLogLevel=debug`.

### Exit codes

| Kode | Betydning |
|---|---|
| `0` | køen er tømt (idle timeout) eller `MAX_MESSAGES` er nået |
| `1` | RabbitMQ-fejl: kan ikke forbinde, login afvist, topologi afvist (`PRECONDITION_FAILED`), forbindelse tabt, afsendelse fejlede |
| `2` | ugyldig konfiguration (fx `MAX_MESSAGES=abc`) |

Ved en kode ≠ 0 bliver alle ikke-kvitterede beskeder i køen; i Kubernetes starter Job'ens backoff et nyt forsøg.

## Kør lokalt

Jobbet har compose-profilen `jobs`, så det **ikke** starter med en almindelig `docker compose up`.

```bash
# stakken kører allerede (docker compose up -d); byg og kør jobbet én gang
docker compose build notification-job
docker compose --profile jobs up notification-job

# eller som engangscontainer, der fjernes bagefter
docker compose --profile jobs run --rm notification-job
```

Lav nogle events først, fx med `./scripts/e2e-smoke.sh` (opretter, betaler og annullerer bookinger) eller ved at
booke i frontenden på http://localhost:8080. Antallet af ventende beskeder kan ses i RabbitMQ's management-UI under
*Queues* → `notifications` (http://localhost:15672, airport/airport).

Håndlavede testbeskeder skal lægges **direkte i køen** via default exchange (routing key = kønavn) – ikke på
`airport.events`. Dér ville de også ramme flight-, payment- og baggage-service, der lytter på `booking.#` og
handler på dem (sæde frigives, snapshot opdateres, ugyldig JSON ender i deres DLQ'er):

```bash
BODY='{"eventId":"test-1","eventType":"booking.confirmed","payload":{"bookingReference":"TEST01",
  "flightNumber":"SK1501","departureTime":"2026-09-20T14:00:00Z","passenger":{"firstName":"Bo","email":"bo@example.com"}}}'
jq -cn --arg p "$BODY" '{properties:{content_type:"application/json"},routing_key:"notifications",payload:$p,payload_encoding:"string"}' \
  | curl -s -u airport:airport -H 'content-type: application/json' -d @- \
      http://localhost:15672/api/exchanges/%2F/amq.default/publish          # -> {"routed":true}
```

Samme image uden compose – containeren skal på compose-netværket for at kunne nå `rabbitmq`:

```bash
docker build -t airport/notification-job:local notification-job
docker run --rm --network dls_exam_default -e RABBITMQ_HOST=rabbitmq airport/notification-job:local
echo "exit code: $?"
```

Uden Docker (JDK 21 + Maven; RabbitMQ fra compose på localhost:5672):

```bash
mvn -B -DskipTests package
java -jar target/notification-job.jar
```

Eksempel på output fra `docker compose --profile jobs up notification-job` efter `./scripts/e2e-smoke.sh`
(forkortet til én af de fem mails; tider i loggen er UTC, tider i mailen dansk tid):

```
notification-job-1  | 2026-09-16T19:11:32.923Z INFO Main - notification-job starting: host=rabbitmq:5672 username=airport queue=notifications maxMessages=100 idleTimeout=5000ms
notification-job-1  | 2026-09-16T19:11:33.064Z INFO NotificationJob - Consuming from notifications (prefetch 1, max 100 messages, idle timeout 5000 ms)
notification-job-1  | 2026-09-16T19:11:33.092Z INFO LoggingMailSender - Mail (simulated, not sent) eventType=booking.confirmed eventId=e5a327e3-a441-4bbd-868d-e601a123b887
notification-job-1  |   Til:  smoke-1789585876@example.com
notification-job-1  |   Emne: Din booking CKVS6E er bekræftet
notification-job-1  |   | Hej Smoke,
notification-job-1  |   |
notification-job-1  |   | Vi har modtaget din betaling, og din booking er nu bekræftet.
notification-job-1  |   |
notification-job-1  |   | Bookingreference: CKVS6E
notification-job-1  |   | Fly:              SK1409
notification-job-1  |   | Afgang:           16. september 2026 kl. 14:30 (dansk tid)
notification-job-1  |   | Sæde:             25F
notification-job-1  |   | Pris:             649,00 DKK
notification-job-1  |   |
notification-job-1  |   | God rejse!
notification-job-1  |   |
notification-job-1  |   | Venlig hilsen
notification-job-1  |   | Lufthavnen
   ...
notification-job-1  | 2026-09-16T19:11:38.102Z INFO Main - notification-job done (IDLE): received=5 sent=5 skipped=0 rejected=0
notification-job-1 exited with code 0
```

## Test

```bash
mvn -B verify          # unit tests + integrationstest (kræver Docker til Testcontainers)
mvn -B -Pci verify     # + Checkstyle og SpotBugs/find-sec-bugs, som i CI
```

- `MailRendererTest` – emne og tekst for hver af de fire eventtyper, årsag ved annullering, dansk tid og talformat,
  ukendte felter, eventtyper uden skabelon og ugyldige beskeder.
- `JobConfigTest` – defaults, overrides, ugyldige værdier (exit code 2), password ikke i `toString`.
- `NotificationJobIntegrationTest` (Testcontainers `rabbitmq:3.13-management-alpine`) – kører hele jobbet
  (`Main.run`): 2 gyldige + 1 ugyldig besked → 2 mails, den ugyldige i `notification-job.dlq`, køen tom, exit 0;
  ukendt eventtype ackes og springes over; `MAX_MESSAGES` efterlader resten urørt; tom kø stopper efter idle
  timeout; kø med andre argumenter, forkert password og utilgængelig broker giver exit 1. Topologien erklæres først
  præcis som `booking-service` gør, så testen også beviser, at de to erklæringer er identiske.

## Kubernetes

Dev plan DP-21 kører samme image som en KEDA `ScaledJob` med en `rabbitmq`-trigger på køen `notifications`: KEDA
starter et Job, når der ligger beskeder i køen, og ingen pods, når den er tom. Jobbet selv skal ikke vide noget om
KEDA – det tømmer køen og stopper, og `MAX_MESSAGES` sikrer, at ét job ikke kører i det uendelige, hvis der hele tiden
kommer nye events.
