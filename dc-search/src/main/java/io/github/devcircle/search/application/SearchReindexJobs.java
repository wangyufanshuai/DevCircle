package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Bounded, explicitly driven maintenance batches; no in-memory job or background thread is authoritative. */
@Service
public class SearchReindexJobs {
    private static final String EMPTY_HASH = "0".repeat(64);
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final SearchIndexService source;
    private final SearchProjectionStore projection;
    private final ObjectMapper mapper;

    SearchReindexJobs(JdbcClient jdbc, PlatformTransactionManager manager, SearchIndexService source,
                      SearchProjectionStore projection, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.source = source;
        this.projection = projection;
        this.mapper = mapper;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(60);
    }

    public Job start(UUID id, boolean maintenanceConfirmed) {
        if (!maintenanceConfirmed) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请先停止内容写入、审核和搜索消费者，再确认维护窗口");
        return locked(() -> {
            var existing = find(id);
            if (existing.isPresent()) return existing.get();
            requireNoActiveJob();
            jdbc.sql("""
                    INSERT INTO search_reindex_job(job_id, phase, copy_hash, verify_hash)
                    VALUES (:id, 'COPYING', :hash, :hash)
                    """).param("id", id.toString()).param("hash", EMPTY_HASH).update();
            jdbc.sql("UPDATE search_reindex_guard SET active_job_id=:id WHERE guard_id=1")
                    .param("id", id.toString()).update();
            return get(id);
        });
    }

    public Job get(UUID id) {
        return find(id).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "重建任务不存在"));
    }

    public Job step(UUID id, long expectedRevision) {
        if (expectedRevision < 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "任务版本不能为负数");
        return locked(() -> {
            Job job = get(id);
            if (expectedRevision > job.revision()) throw conflict("任务版本不匹配");
            // A timed-out client can safely repeat its last revision without advancing twice.
            if (expectedRevision < job.revision() || job.terminal()) return job;
            requireOwner(id);
            if (!job.phase().equals("COPYING") && !job.phase().equals("VERIFYING")) throw conflict("重建任务状态无效");
            SearchIndexService.ContentBatch batch = source.loadContentBatch(job.cursor());
            if (job.phase().equals("COPYING")) {
                String hash = job.copyHash();
                for (var post : batch.records()) {
                    var document = source.document(post, post.createdAt());
                    projection.upsert(document);
                    hash = chain(hash, SearchProjectionStore.businessState(document));
                }
                return save(job, batch.hasMore() ? "COPYING" : "VERIFYING", batch.nextCursor(),
                        Math.addExact(job.copied(), batch.records().size()), 0, hash, EMPTY_HASH, null);
            }
            String hash = job.verifyHash();
            for (var post : batch.records()) {
                var expected = SearchProjectionStore.businessState(source.document(post, post.createdAt()));
                var actual = SearchProjectionStore.businessState(projection.get(post.id()));
                if (!expected.equals(actual)) {
                    var fields = new java.util.TreeSet<String>(expected.keySet());
                    fields.addAll(actual.keySet());
                    String different = fields.stream().filter(key -> !Objects.equals(expected.get(key), actual.get(key)))
                            .collect(Collectors.joining(","));
                    return fail(job, "PROJECTION_MISMATCH id=" + post.id() + " fields=" + different
                            + " expected=" + chain(EMPTY_HASH, expected) + " actual=" + chain(EMPTY_HASH, actual));
                }
                hash = chain(hash, expected);
            }
            long verified = Math.addExact(job.verified(), batch.records().size());
            if (batch.hasMore()) return save(job, "VERIFYING", batch.nextCursor(), job.copied(), verified,
                    job.copyHash(), hash, null);
            if (verified != job.copied() || !hash.equals(job.copyHash())) return fail(job, "SOURCE_CHANGED_DURING_REINDEX");
            projection.refresh();
            if (!projection.countsMatch(verified)) return fail(job, "INDEX_EXTRA_OR_INVALID_DOCUMENTS");
            return save(job, "COMPLETED", "", job.copied(), verified, job.copyHash(), hash, null);
        });
    }

    public Job cancel(UUID id) {
        return locked(() -> {
            Job job = get(id);
            if (job.terminal()) return job;
            requireOwner(id);
            return save(job, "CANCELLED", job.cursor(), job.copied(), job.verified(), job.copyHash(), job.verifyHash(), "CANCELLED_BY_OPERATOR");
        });
    }

    /** Compatibility endpoint also participates in the guard, so it cannot bypass an active task. */
    public int legacyReindex() {
        return locked(() -> { requireNoActiveJob(); return source.reindexSeedPosts(); });
    }

    private <T> T locked(Supplier<T> operation) {
        try {
            return transaction.execute(status -> {
                jdbc.sql("SELECT guard_id FROM search_reindex_guard WHERE guard_id=1 FOR UPDATE NOWAIT")
                        .query(Integer.class).single();
                return operation.get();
            });
        } catch (PessimisticLockingFailureException busy) {
            throw conflict("另一批重建操作尚未结束，请稍后重试");
        } catch (BusinessException known) {
            throw known;
        } catch (RuntimeException failure) {
            // MySQL 8.4 NOWAIT uses HY000/3572, which Spring does not classify as a lock exception.
            if (failure instanceof org.springframework.jdbc.UncategorizedSQLException sql
                    && sql.getSQLException().getErrorCode() == 3572) {
                throw conflict("另一批重建操作尚未结束，请稍后重试");
            }
            BusinessException safe = new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "重建批次未确认完成，请查询任务进度后重试");
            safe.initCause(failure);
            throw safe;
        }
    }

    private void requireNoActiveJob() {
        if (activeId() != null) throw conflict("已有重建任务，须继续或取消后再创建");
    }

    private void requireOwner(UUID id) {
        if (!id.toString().equals(activeId())) throw conflict("重建任务不再拥有执行权");
    }

    private String activeId() {
        return jdbc.sql("SELECT active_job_id FROM search_reindex_guard WHERE guard_id=1")
                .query((rs, row) -> new Guard(rs.getString(1))).single().id();
    }

    private java.util.Optional<Job> find(UUID id) {
        return jdbc.sql("SELECT * FROM search_reindex_job WHERE job_id=:id").param("id", id.toString())
                .query((rs, row) -> new Job(UUID.fromString(rs.getString("job_id")), rs.getString("phase"),
                        rs.getString("cursor_value"), rs.getLong("copied_count"), rs.getLong("verified_count"),
                        rs.getString("copy_hash"), rs.getString("verify_hash"), rs.getLong("revision"),
                        rs.getString("last_error"))).optional();
    }

    private Job fail(Job job, String error) {
        return save(job, "FAILED", job.cursor(), job.copied(), job.verified(), job.copyHash(), job.verifyHash(), error);
    }

    private Job save(Job job, String phase, String cursor, long copied, long verified, String copyHash, String verifyHash, String error) {
        int changed = jdbc.sql("""
                UPDATE search_reindex_job SET phase=:phase, cursor_value=:cursor, copied_count=:copied,
                    verified_count=:verified, copy_hash=:copyHash, verify_hash=:verifyHash,
                    revision=revision+1, last_error=:error, updated_at=CURRENT_TIMESTAMP
                WHERE job_id=:id AND revision=:revision
                """).param("phase", phase).param("cursor", cursor).param("copied", copied).param("verified", verified)
                .param("copyHash", copyHash).param("verifyHash", verifyHash).param("error", error)
                .param("id", job.jobId().toString()).param("revision", job.revision()).update();
        if (changed != 1) throw conflict("重建检查点已改变");
        Job result = get(job.jobId());
        if (result.terminal()) jdbc.sql("UPDATE search_reindex_guard SET active_job_id=NULL WHERE guard_id=1") .update();
        return result;
    }

    private String chain(String previous, Object value) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(previous.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest(mapper.writeValueAsBytes(value)));
        } catch (java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Cannot fingerprint reindex state", failure);
        }
    }

    private BusinessException conflict(String message) { return new BusinessException(ErrorCode.VERSION_CONFLICT, message); }
    private record Guard(String id) { }

    public record Job(UUID jobId, String phase, String cursor, long copied, long verified,
                      String copyHash, String verifyHash, long revision, String lastError) {
        public boolean terminal() { return phase.equals("COMPLETED") || phase.equals("FAILED") || phase.equals("CANCELLED"); }
    }
}
