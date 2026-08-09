# Search-in-Discovery non-container evidence

## Binding

- Commit: `c14d6fc1db816b457a179504457053b77817768e`
- Run: Windows PowerShell, local time 2026-08-09 21:35–21:37 (UTC 13:35–13:37; approximate terminal window)
- Scope: offline/non-container verification of the Search-in-Discovery first-phase boundary and the current production resource contract.

## Commands

```powershell
.\mvnw.cmd -B clean verify -Pcoverage
python -m unittest discover -s scripts/ops -p "test_*.py" -v
python scripts/ops/assert-critical-state-coverage.py
python scripts/ops/assert-production-resource-budgets.py --env-file .env.production.example
docker compose --env-file .env.production.example -f docker-compose.prod.yml config --quiet
```

## Results

- Maven: 19/19 Reactor projects succeeded.
- Surefire: 108 fresh XML reports; 474 tests; 0 failures, 0 errors, 0 skipped.
- JaCoCo: 17 XML reports; aggregate line coverage 81.17%; aggregate branch coverage 73.89%.
- Critical-state coverage gate: passed (all required state-machine branch thresholds).
- Operations contract tests: passed.
- Production resource contract: 22 Compose services, 13.62 GiB declared container memory, 2 host-published services (Web and Gateway only).
- Compose static syntax/configuration: passed with `config --quiet`.

## Architecture decision recorded by this evidence

`dc-discovery` is the production-facing Discovery boundary. The `dc-search` code module is carried by the `dc-discovery` process in the first phase; the legacy standalone Search process remains available only through [`docker-compose.legacy-search-rollback.yml`](../../deploy/compose/docker-compose.legacy-search-rollback.yml) for rollback. Recommendation is an independent `dc-recommend` process: Gateway routes `/api/recommend/**` directly to it, and recommendation runtime is not embedded in Discovery.

## Exclusions

This run did not start Docker containers. It therefore does not prove Testcontainers, real Docker Compose startup/E2E, target Linux performance, Linux recovery/RTO/RPO, Helm deployment/render in a cluster, or Cosign verification against a registry. The resource count is a static configuration gate, not a runtime capacity or availability result. Those claims require a fresh run on the target Linux host and a registry-backed release.
