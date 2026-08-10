# Auth and Gateway architecture slice evidence (2026-08-10)

## Evidence identity

- Branch: `overhaul/mysticverse-production`
- Auth commit: `9a001416cc204bf2bc693aea666764035b7bbffb`
- Gateway commit: `c5d280b7f07efa12a8b0c19172ac9618fa3f02fe`
- Host: Windows 11, Java 21
- Scope: authentication persistence responsibility split and shared Gateway rate limiting

## Delivered architecture

- `AuthTokenRepository` remains a public compatibility facade for one migration window.
- Account/OAuth binding, OAuth one-time state, refresh-session rotation/revocation, audit serialization,
  and token hashing now have separate focused persistence collaborators.
- Existing constructors, records, public methods, transaction annotations, and
  `TokenRevokedException` no-rollback behavior remain compatible with current callers.
- Gateway rate limiting no longer stores production counters in a JVM-local map.
- `RedisRateLimitStore` uses an atomic Redis Lua `INCR` + first-write `EXPIRE` fixed-window operation.
- The key includes the client identity, auth/write/read bucket, and UTC epoch minute, so separate
  Gateway replicas share the same budget.
- Redis errors fail closed with `503 RATE_LIMIT_DEPENDENCY_UNAVAILABLE`; there is no production memory fallback.

## Verification executed on this SHA

```powershell
.\mvnw.cmd -B -pl dc-auth -am `
  '-Dtest=AuthTokenRepositoryOAuthTest,RefreshTokenRotationTest,AuthServiceBoundaryTest,AuthServiceIdempotencyTest,GitHubOAuthServiceTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -B -pl dc-gateway -am test
.\mvnw.cmd -B clean verify -Pcoverage `
  '-Dtest=!*IntegrationTest,!DockerPreflightTest,!KafkaContainerSmokeTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false'
python -m unittest discover -s scripts/ops -p 'test*.py'
bash -n scripts/performance/run-linux-acceptance.sh
bash -n scripts/ops/finalize-linux-recovery-evidence.sh
```

Results:

- Auth targeted tests: 28 passed, 0 failed/error/skipped.
- Gateway module tests: 40 passed, 0 failed/error/skipped.
- Full non-container Reactor: 19/19 projects succeeded.
- Full non-container Surefire: 478 tests, 0 failures, 0 errors, 0 skipped.
- Aggregate JaCoCo: line 81.15%, branch 74.27%.
- Critical state-machine branch gate: all 14 classes at or above 85%.
- Ops Python: 49 tests passed.
- Shell syntax and `git diff --check`: passed.
- Frontend `npm run build`: passed.

## Evidence boundary

The local Docker Linux Engine was unavailable (`docker info` timed out). Therefore this evidence does
not claim that the Redis Lua script has run against a real Redis container, nor does it prove
Testcontainers, production Compose, real authentication/community E2E, Linux 50-concurrency P95,
Kafka/ES/Redis recovery, formal RTO/RPO, Helm deployment, or registry-backed Cosign/SBOM/provenance
verification. Those gates must be rerun against the current release SHA on the target Linux host.
