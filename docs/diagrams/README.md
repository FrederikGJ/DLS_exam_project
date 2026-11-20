# Diagrammer

Diagrammerne er tegnet i [draw.io](https://www.drawio.com/) (desktop: `winget install JGraph.Draw`). `.drawio` er den
redigérbare kilde; `.png` er eksporten, som bruges i `docs/architecture.md`, rapporten og slides.

| Diagram | Viser | Bruges i |
|---|---|---|
| `kubernetes-deployment` | kind-clusteret: namespaces, Ingress → Service → Deployment, HPA, StatefulSets, RabbitMQ, KEDA-funktionen | architecture.md "Kubernetes-deployment" |
| `local-docker` | docker compose: containere, porte på localhost, Docker-netværk, volumes | architecture.md "Lokal udvikling" |
| `cloud-production-plan` | hypotetisk plan på Azure Kubernetes Service | architecture.md "Produktionsplan i skyen" |

Alle diagrammer følger de samme regler (fra kurset): en signaturforklaring for stregtyperne, RabbitMQ som selvstændig
node, én database pr. service, og pods tales aldrig til direkte (altid en Service foran).

**Efter en ændring:** gem `.drawio` og eksportér PNG igen, så de to ikke kommer ud af sync:

```powershell
& "C:\Program Files\draw.io\draw.io.exe" -x -f png -s 2 -b 20 -o docs/diagrams/kubernetes-deployment.png docs/diagrams/kubernetes-deployment.drawio
```
