#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEMP_ROOT="$(mktemp -d)"
TEMP_ROOT="$(cd "${TEMP_ROOT}" && pwd -P)"
TEMP_PREFIX="$(cd "${TMPDIR:-/tmp}" && pwd -P)/tmp."
cleanup() {
  case "${TEMP_ROOT}" in
    "${TEMP_PREFIX}"*) rm -rf -- "${TEMP_ROOT}" ;;
    *) echo "Refusing to remove unexpected temporary path: ${TEMP_ROOT}" >&2 ;;
  esac
}
trap cleanup EXIT

FAKE_BIN="${TEMP_ROOT}/bin"
ENV_FILE="${TEMP_ROOT}/production.env"
BACKUP_ROOT="${TEMP_ROOT}/backups"
FAKE_DOCKER_LOG="${TEMP_ROOT}/docker.log"
mkdir -p "${FAKE_BIN}"

cat > "${FAKE_BIN}/docker" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%q ' "$@" >> "${FAKE_DOCKER_LOG}"
printf '\n' >> "${FAKE_DOCKER_LOG}"
command_line="$*"
case "${command_line}" in
  *mysqldump*) printf '%s\n' '-- deterministic fake SQL dump' ;;
  *'tar -C /minio-data -czf - .'*) printf '%s\n' 'deterministic fake MinIO archive' ;;
  *'SELECT COUNT(*) FROM post WHERE status'*) printf '%s\n' "${FAKE_PUBLISHED_POST_COUNT:-180}" ;;
  *'SELECT COUNT(*) FROM post'*) printf '%s\n' "${FAKE_POST_COUNT:-180}" ;;
  *'SHA2(CONCAT_WS'*)
    if [[ "${FAKE_CONTENT_VARIANT:-stable}" == "changed" ]] || \
       { [[ "${FAKE_CONTENT_VARIANT:-stable}" == "changed-after-dump" ]] && grep -q 'mysqldump' "${FAKE_DOCKER_LOG}"; }; then
      printf '1\trow-hash-one\n2\trow-hash-changed\n'
    else
      printf '1\trow-hash-one\n2\trow-hash-two\n'
    fi
    ;;
  *'devcircle-posts/_count?q=-status'*) printf '{"count":%s,"_shards":{"total":1}}\n' "${FAKE_ES_COUNT:-0}" ;;
  *'devcircle-posts/_count'*) printf '{"count":%s,"_shards":{"total":1}}\n' "${FAKE_ES_INDEXED_COUNT:-180}" ;;
  *'exec mysql '*|*'tar -C /minio-data -xzf -'*) cat >/dev/null ;;
esac
EOF
chmod +x "${FAKE_BIN}/docker"

cat > "${ENV_FILE}" <<'EOF'
IMAGE_TAG=4f1a9c2e8b7d6a5f0e3c2b1a9876543210abcdef
DOMAIN=mysticverse.test
PUBLIC_ORIGIN=https://mysticverse.test
TLS_EMAIL=ops@mysticverse.test
MYSQL_USER=mv_prod_operator
MYSQL_PASSWORD=MySQL-7u!pR2#xV9@cK4~zN8qL
MYSQL_ROOT_PASSWORD=RootDB-3m@T8!wQ5#jH7~xP2vC
REDIS_PASSWORD=Redis-6k#Y1@bN9!sF4~xU8qW
MINIO_ROOT_USER=mv_object_admin
MINIO_ROOT_PASSWORD=Object-9z!D2@rM7#pL4~xC6vB
DEVCIRCLE_JWT_SECRET=Aa1!Bb2@Cc3#Dd4~Ee5%Ff6^Gg7&Hh8*Ii9(Jj0)Kk1_Ll2+Mm3-Nn4=Oo5?Pp6Qq7~
EOF

export PATH="${FAKE_BIN}:${PATH}"
export FAKE_DOCKER_LOG

ENV_FILE="${ENV_FILE}" BACKUP_ROOT="${BACKUP_ROOT}" BACKUP_RETENTION_DAYS=365 \
  bash "${ROOT_DIR}/scripts/ops/backup.sh" >/dev/null

mapfile -t backup_dirs < <(find "${BACKUP_ROOT}" -mindepth 1 -maxdepth 1 -type d)
(( ${#backup_dirs[@]} == 1 )) || { echo "Expected exactly one backup directory" >&2; exit 1; }
BACKUP_DIR="${backup_dirs[0]}"
test -s "${BACKUP_DIR}/mysql.sql.gz" \
  -a -s "${BACKUP_DIR}/minio-data.tar.gz" \
  -a -s "${BACKUP_DIR}/backup-manifest.json" \
  -a -s "${BACKUP_DIR}/SHA256SUMS"
(cd "${BACKUP_DIR}" && sha256sum -c SHA256SUMS >/dev/null)
gunzip -c "${BACKUP_DIR}/mysql.sql.gz" | grep -q 'deterministic fake SQL dump'

# shellcheck source=scripts/ops/backup-manifest.sh
source "${ROOT_DIR}/scripts/ops/backup-manifest.sh"
verify_manifest_artifacts "${BACKUP_DIR}"
test "${MANIFEST_SCHEMA_VERSION}" = "1"
test "${MANIFEST_POST_COUNT}" = "180"
test "${MANIFEST_PUBLISHED_POST_COUNT}" = "180"
test "${MANIFEST_IMAGE_TAG}" = "4f1a9c2e8b7d6a5f0e3c2b1a9876543210abcdef"
[[ "${MANIFEST_CREATED_AT}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T ]]
[[ "${MANIFEST_CONTENT_HASH}" =~ ^[0-9a-f]{64}$ ]]
grep -q 'backup-manifest.json' "${BACKUP_DIR}/SHA256SUMS"

ENV_FILE="${ENV_FILE}" CONFIRM_RESTORE=RESTORE \
  bash "${ROOT_DIR}/scripts/ops/restore.sh" "${BACKUP_DIR}" >/dev/null

verification="$(ENV_FILE="${ENV_FILE}" EXPECTED_POST_COUNT=180 FAKE_ES_COUNT=0 \
  bash "${ROOT_DIR}/scripts/ops/verify-migration.sh")"
[[ "${verification}" == *'"postCount":180'* && "${verification}" == *'"unexpectedSearchDocuments":0'* ]]
[[ "${verification}" == *'"publishedPostCount":180'* && "${verification}" == *'"indexedSearchDocuments":180'* ]]

manifest_verification="$(ENV_FILE="${ENV_FILE}" BACKUP_MANIFEST="${BACKUP_DIR}/backup-manifest.json" FAKE_ES_COUNT=0 \
  bash "${ROOT_DIR}/scripts/ops/verify-migration.sh")"
[[ "${manifest_verification}" == *'"contentChecksum":"'* ]]

if ENV_FILE="${ENV_FILE}" CONFIRM_RESTORE=RESTORE FAKE_POST_COUNT=179 \
  bash "${ROOT_DIR}/scripts/ops/restore.sh" "${BACKUP_DIR}" >/dev/null 2>&1; then
  echo "Expected a restored post count mismatch to block application restart" >&2
  exit 1
fi

if ENV_FILE="${ENV_FILE}" CONFIRM_RESTORE=RESTORE FAKE_CONTENT_VARIANT=changed \
  bash "${ROOT_DIR}/scripts/ops/restore.sh" "${BACKUP_DIR}" >/dev/null 2>&1; then
  echo "Expected a restored content hash mismatch to block application restart" >&2
  exit 1
fi

if ENV_FILE="${ENV_FILE}" BACKUP_MANIFEST="${BACKUP_DIR}/backup-manifest.json" FAKE_CONTENT_VARIANT=changed \
  bash "${ROOT_DIR}/scripts/ops/verify-migration.sh" >/dev/null 2>&1; then
  echo "Expected a manifest content hash mismatch to block migration verification" >&2
  exit 1
fi

TAMPERED_BACKUP="${TEMP_ROOT}/tampered-backup"
cp -a "${BACKUP_DIR}" "${TAMPERED_BACKUP}"
printf 'tampered' >> "${TAMPERED_BACKUP}/mysql.sql.gz"
if ENV_FILE="${ENV_FILE}" CONFIRM_RESTORE=RESTORE \
  bash "${ROOT_DIR}/scripts/ops/restore.sh" "${TAMPERED_BACKUP}" >/dev/null 2>&1; then
  echo "Expected a modified backup artifact to block restore" >&2
  exit 1
fi

INVALID_MANIFEST_BACKUP="${TEMP_ROOT}/invalid-manifest-backup"
cp -a "${BACKUP_DIR}" "${INVALID_MANIFEST_BACKUP}"
sed -i 's/"mysqlSha256": "[0-9a-f]*"/"mysqlSha256": "0000000000000000000000000000000000000000000000000000000000000000"/' \
  "${INVALID_MANIFEST_BACKUP}/backup-manifest.json"
(
  cd "${INVALID_MANIFEST_BACKUP}"
  sha256sum mysql.sql.gz minio-data.tar.gz backup-manifest.json > SHA256SUMS
)
if ENV_FILE="${ENV_FILE}" CONFIRM_RESTORE=RESTORE \
  bash "${ROOT_DIR}/scripts/ops/restore.sh" "${INVALID_MANIFEST_BACKUP}" >/dev/null 2>&1; then
  echo "Expected a manifest artifact checksum mismatch to block restore" >&2
  exit 1
fi

LEGACY_BACKUP="${TEMP_ROOT}/legacy-backup"
cp -a "${BACKUP_DIR}" "${LEGACY_BACKUP}"
rm "${LEGACY_BACKUP}/backup-manifest.json"
(
  cd "${LEGACY_BACKUP}"
  sha256sum mysql.sql.gz minio-data.tar.gz > SHA256SUMS
)
if ENV_FILE="${ENV_FILE}" CONFIRM_RESTORE=RESTORE \
  bash "${ROOT_DIR}/scripts/ops/restore.sh" "${LEGACY_BACKUP}" >/dev/null 2>&1; then
  echo "Expected a legacy backup to require explicit approval" >&2
  exit 1
fi
ENV_FILE="${ENV_FILE}" CONFIRM_RESTORE=RESTORE ALLOW_LEGACY_BACKUP=true \
  bash "${ROOT_DIR}/scripts/ops/restore.sh" "${LEGACY_BACKUP}" >/dev/null 2>&1

DRIFT_BACKUP_ROOT="${TEMP_ROOT}/drift-backups"
DRIFT_DOCKER_LOG="${TEMP_ROOT}/drift-docker.log"
if ENV_FILE="${ENV_FILE}" BACKUP_ROOT="${DRIFT_BACKUP_ROOT}" BACKUP_RETENTION_DAYS=365 \
  FAKE_DOCKER_LOG="${DRIFT_DOCKER_LOG}" FAKE_CONTENT_VARIANT=changed-after-dump \
  bash "${ROOT_DIR}/scripts/ops/backup.sh" >/dev/null 2>&1; then
  echo "Expected content changes during capture to block backup publication" >&2
  exit 1
fi
if find "${DRIFT_BACKUP_ROOT}" -mindepth 1 -maxdepth 1 -type d | grep -q .; then
  echo "Expected failed backup artifacts to be removed" >&2
  exit 1
fi

if ENV_FILE="${ENV_FILE}" EXPECTED_POST_COUNT=180 FAKE_ES_COUNT=0 FAKE_ES_INDEXED_COUNT=179 \
  bash "${ROOT_DIR}/scripts/ops/verify-migration.sh" >/dev/null 2>&1; then
  echo "Expected an Elasticsearch document count mismatch to block verification" >&2
  exit 1
fi

if ENV_FILE="${ENV_FILE}" EXPECTED_POST_COUNT=180 FAKE_ES_COUNT=0 FAKE_PUBLISHED_POST_COUNT=179 \
  bash "${ROOT_DIR}/scripts/ops/verify-migration.sh" >/dev/null 2>&1; then
  echo "Expected a published database count mismatch to block verification" >&2
  exit 1
fi

if ENV_FILE="${ENV_FILE}" EXPECTED_POST_COUNT=180 FAKE_ES_COUNT=1 \
  bash "${ROOT_DIR}/scripts/ops/verify-migration.sh" >/dev/null 2>&1; then
  echo "Expected non-published Elasticsearch documents to block verification" >&2
  exit 1
fi

grep -q 'mysqldump' "${FAKE_DOCKER_LOG}"
grep -q ' stop ' "${FAKE_DOCKER_LOG}"
grep -q ' start ' "${FAKE_DOCKER_LOG}"
if grep -Eq '\+[[:space:]]+--|sh[[:space:]]+-c[[:space:]]+\+' \
  "${ROOT_DIR}/scripts/ops/backup.sh" "${ROOT_DIR}/scripts/ops/verify-migration.sh"; then
  echo "Found a literal patch marker in an operations command" >&2
  exit 1
fi

echo "Backup, restore, and migration verifier tests passed"
