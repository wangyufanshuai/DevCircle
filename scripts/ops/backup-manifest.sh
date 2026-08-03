#!/usr/bin/env bash

manifest_string_field() {
  local manifest_path="$1"
  local field="$2"
  sed -n "s/^[[:space:]]*\"${field}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\"[[:space:]]*,\{0,1\}[[:space:]]*$/\1/p" \
    "${manifest_path}"
}

manifest_number_field() {
  local manifest_path="$1"
  local field="$2"
  sed -n "s/^[[:space:]]*\"${field}\"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\)[[:space:]]*,\{0,1\}[[:space:]]*$/\1/p" \
    "${manifest_path}"
}

load_backup_manifest() {
  local manifest_path="$1"
  test -f "${manifest_path}" || {
    echo "Missing backup manifest: ${manifest_path}" >&2
    return 1
  }

  MANIFEST_SCHEMA_VERSION="$(manifest_number_field "${manifest_path}" schemaVersion)"
  MANIFEST_CREATED_AT="$(manifest_string_field "${manifest_path}" createdAt)"
  MANIFEST_GIT_COMMIT="$(manifest_string_field "${manifest_path}" gitCommit)"
  MANIFEST_IMAGE_TAG="$(manifest_string_field "${manifest_path}" imageTag)"
  MANIFEST_POST_COUNT="$(manifest_number_field "${manifest_path}" postCount)"
  MANIFEST_PUBLISHED_POST_COUNT="$(manifest_number_field "${manifest_path}" publishedPostCount)"
  MANIFEST_CONTENT_HASH="$(manifest_string_field "${manifest_path}" contentHash)"
  MANIFEST_MYSQL_SHA256="$(manifest_string_field "${manifest_path}" mysqlSha256)"
  MANIFEST_MINIO_SHA256="$(manifest_string_field "${manifest_path}" minioSha256)"

  test "${MANIFEST_SCHEMA_VERSION}" = "1" || {
    echo "Unsupported backup manifest schema: ${MANIFEST_SCHEMA_VERSION:-missing}" >&2
    return 1
  }
  [[ "${MANIFEST_CREATED_AT}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || {
    echo "Invalid backup manifest createdAt" >&2
    return 1
  }
  [[ "${MANIFEST_GIT_COMMIT}" =~ ^[0-9a-fA-F]{40}$ ]] || {
    echo "Invalid backup manifest gitCommit" >&2
    return 1
  }
  [[ "${MANIFEST_IMAGE_TAG}" =~ ^[0-9a-fA-F]{40}$ ]] || {
    echo "Invalid backup manifest imageTag" >&2
    return 1
  }
  [[ "${MANIFEST_POST_COUNT}" =~ ^[0-9]+$ && "${MANIFEST_PUBLISHED_POST_COUNT}" =~ ^[0-9]+$ ]] || {
    echo "Invalid backup manifest content counts" >&2
    return 1
  }
  [[ "${MANIFEST_CONTENT_HASH}" =~ ^[0-9a-f]{64}$ ]] || {
    echo "Invalid backup manifest contentHash" >&2
    return 1
  }
  [[ "${MANIFEST_MYSQL_SHA256}" =~ ^[0-9a-f]{64}$ && "${MANIFEST_MINIO_SHA256}" =~ ^[0-9a-f]{64}$ ]] || {
    echo "Invalid backup manifest artifact checksums" >&2
    return 1
  }
}

verify_manifest_artifacts() {
  local backup_dir="$1"
  local actual_mysql_sha256
  local actual_minio_sha256

  load_backup_manifest "${backup_dir}/backup-manifest.json"
  test -f "${backup_dir}/mysql.sql.gz" -a -f "${backup_dir}/minio-data.tar.gz" || {
    echo "Backup artifacts referenced by the manifest are missing" >&2
    return 1
  }

  actual_mysql_sha256="$(sha256sum "${backup_dir}/mysql.sql.gz" | awk '{print $1}')"
  actual_minio_sha256="$(sha256sum "${backup_dir}/minio-data.tar.gz" | awk '{print $1}')"
  test "${actual_mysql_sha256}" = "${MANIFEST_MYSQL_SHA256}" || {
    echo "MySQL backup checksum does not match backup-manifest.json" >&2
    return 1
  }
  test "${actual_minio_sha256}" = "${MANIFEST_MINIO_SHA256}" || {
    echo "MinIO backup checksum does not match backup-manifest.json" >&2
    return 1
  }
}
