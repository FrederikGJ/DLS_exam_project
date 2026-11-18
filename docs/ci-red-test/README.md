# Rød test giver rød pipeline (task 1.3)

Mappen dokumenterer, at CI-pipelinen faktisk fejler, når en test fejler. Den 18-11-2026 blev der lavet en midlertidig
branch, `ci-red-test`, ud fra `dev_mandatory2_frederik` (commit `284adf5`). Den eneste ændring var testen
`CiRedTest.failsOnPurpose` i flight-service, som bevidst sammenligner 1 med 2. Branchen blev pushet til GitHub, og
GitHub Actions kørte det almindelige workflow ([.github/workflows/ci.yml](../../.github/workflows/ci.yml)).

Screenshottet [red-test-pipeline.png](red-test-pipeline.png) viser resultatet. Jobbet `backend · flight-service` fejlede
efter 1 min 44 s med `Tests run: 25, Failures: 1`, `BUILD FAILURE` og exit code 1. Dermed blev hele kørslen rød.
Testen fejlede selv, så det var ikke Checkstyle eller SpotBugs, der stoppede bygget. De andre matrix-jobs
(booking-, payment-, baggage- og shop-service) og jobbene `frontend`, `manifests` og `contracts` blev grønne.
`fail-fast` er slået fra, så én rød service ikke skjuler resultatet for de andre. `security` og `system` var stadig i
gang, da billedet blev taget.

Bagefter blev branchen slettet igen, både lokalt og på GitHub, så den fejlende test aldrig er kommet ind på en rigtig
branch.
