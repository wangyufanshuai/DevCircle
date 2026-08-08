package io.github.devcircle.admin.application;

import io.github.devcircle.admin.application.query.AdminOpsReadRepository;
import io.github.devcircle.admin.application.query.AdminOpsReadRepository.DegradationMetrics;
import io.github.devcircle.admin.application.query.AdminOpsReadRepository.HealthMetrics;
import io.github.devcircle.admin.application.query.AdminOpsViewAssembler;
import io.github.devcircle.admin.application.query.DegradationView;
import io.github.devcircle.admin.application.query.OpsHealthView;
import io.github.devcircle.admin.application.query.OutboxEventView;
import io.github.devcircle.admin.application.query.OutboxFleetSnapshot;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Service
public class AdminOpsQueryService {
    private static final Logger log = LoggerFactory.getLogger(AdminOpsQueryService.class);
    private static final int DEFAULT_MAX_CONCURRENT_TASKS = 8;

    private final AdminOpsReadRepository repository;
    private final OutboxFleetHealthService fleetHealthService;
    private final ExecutorService executor;
    private final Duration timeout;
    private final Semaphore taskPermits;

    @Autowired
    public AdminOpsQueryService(
            AdminOpsReadRepository repository,
            OutboxFleetHealthService fleetHealthService,
            @Value("${devcircle.admin.ops-query-timeout-ms:2500}") long timeoutMillis) {
        this(repository, fleetHealthService, Executors.newVirtualThreadPerTaskExecutor(),
                Duration.ofMillis(Math.max(100, timeoutMillis)), DEFAULT_MAX_CONCURRENT_TASKS);
    }

    AdminOpsQueryService(AdminOpsReadRepository repository,
                         OutboxFleetHealthService fleetHealthService,
                         ExecutorService executor,
                         Duration timeout,
                         int maxConcurrentTasks) {
        this.repository = repository;
        this.fleetHealthService = fleetHealthService;
        this.executor = executor;
        this.timeout = timeout;
        this.taskPermits = new Semaphore(Math.max(1, maxConcurrentTasks));
    }

    public OpsHealthView health() {
        QueryPair<HealthMetrics> result = queryPair(repository::healthMetrics);
        return AdminOpsViewAssembler.health(result.local(), result.fleet());
    }

    public List<OutboxEventView> outboxEvents(String status, String topic, int page, int size) {
        int effectivePage = Math.max(page, 1);
        int effectiveSize = Math.max(1, Math.min(size, 200));
        long offset = (long) (effectivePage - 1) * effectiveSize;
        try {
            return repository.outboxEvents(blankToNull(status), blankToNull(topic), effectiveSize, offset);
        } catch (DataAccessException ex) {
            throw dependencyUnavailable(ex);
        }
    }

    public DegradationView degradation() {
        QueryPair<DegradationMetrics> result = queryPair(repository::degradationMetrics);
        return AdminOpsViewAssembler.degradation(result.local(), result.fleet());
    }

    private <T> QueryPair<T> queryPair(Supplier<T> localQuery) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        CompletableFuture<T> local = submitBounded(localQuery, "admin-database");
        CompletableFuture<OutboxFleetSnapshot> fleet = submitBounded(fleetHealthService::snapshot, "outbox-fleet");
        T localResult = await(local, deadlineNanos, "admin-database");
        OutboxFleetSnapshot fleetResult = await(fleet, deadlineNanos, "outbox-fleet");
        if (fleetResult == null) fleetResult = fleetHealthService.unavailableSnapshot();
        return new QueryPair<>(localResult, fleetResult);
    }

    private <T> CompletableFuture<T> submitBounded(Supplier<T> task, String dependency) {
        return CompletableFuture.supplyAsync(() -> {
            if (!taskPermits.tryAcquire()) {
                throw new RejectedExecutionException("Admin operations bulkhead is full");
            }
            try {
                return task.get();
            } finally {
                taskPermits.release();
            }
        }, executor).exceptionally(failure -> {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            log.warn("Admin operations dependency unavailable dependency={} failure={} message={}",
                    dependency, cause.getClass().getSimpleName(), cause.getMessage());
            return null;
        });
    }

    private <T> T await(CompletableFuture<T> future, long deadlineNanos, String dependency) {
        if (future.isDone()) return future.getNow(null);
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            future.cancel(true);
            log.warn("Admin operations dependency exceeded shared deadline dependency={}", dependency);
            return null;
        }
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            log.warn("Admin operations dependency timed out dependency={} timeoutMs={}",
                    dependency, timeout.toMillis());
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return null;
        } catch (ExecutionException ex) {
            return null;
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private BusinessException dependencyUnavailable(DataAccessException cause) {
        BusinessException failure = new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "运营数据暂时不可用");
        failure.initCause(cause);
        return failure;
    }

    @PreDestroy
    void close() {
        executor.close();
    }

    private record QueryPair<T>(T local, OutboxFleetSnapshot fleet) {
    }
}
