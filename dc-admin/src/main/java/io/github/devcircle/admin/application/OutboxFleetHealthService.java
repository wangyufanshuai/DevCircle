package io.github.devcircle.admin.application;

import io.github.devcircle.admin.application.query.OutboxFleetSnapshot;
import io.github.devcircle.admin.application.query.OutboxSourceView;
import io.github.devcircle.messaging.outbox.OutboxStatusView;
import io.github.devcircle.common.http.BoundedRestClientFactory;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class OutboxFleetHealthService {
    private static final Logger log = LoggerFactory.getLogger(OutboxFleetHealthService.class);
    private final Map<String, OutboxNodeClient> clients;
    private final ExecutorService executor;
    private final long fleetTimeoutMillis;
    private final Semaphore snapshotPermits;

    @Autowired
    public OutboxFleetHealthService(
            @Value("${devcircle.internal.auth-uri:http://localhost:8101}") String authUri,
            @Value("${devcircle.internal.user-uri:http://localhost:8102}") String userUri,
            @Value("${devcircle.internal.auth-outbox-path:/internal/ops/outbox/status}") String authOutboxPath,
            @Value("${devcircle.internal.user-outbox-path:/internal/ops/outbox/status}") String userOutboxPath,
            @Value("${devcircle.internal.content-uri:http://localhost:8103}") String contentUri,
            @Value("${devcircle.internal.comment-uri:http://localhost:8104}") String commentUri,
            @Value("${devcircle.internal.interaction-uri:http://localhost:8105}") String interactionUri,
            @Value("${devcircle.internal.interaction-outbox-path:/internal/ops/outbox/status}")
            String interactionOutboxPath,
            @Value("${devcircle.internal.ops-key:local-development-internal-ops-key}") String internalOpsKey,
            @Value("${devcircle.admin.outbox-fleet-timeout-ms:2000}") long fleetTimeoutMillis,
            @Value("${devcircle.admin.outbox-fleet-max-concurrent-snapshots:2}") int maxConcurrentSnapshots) {
        this.clients = new LinkedHashMap<>();
        clients.put("dc-auth", new HttpOutboxNodeClient(authUri, authOutboxPath, internalOpsKey));
        clients.put("dc-user", new HttpOutboxNodeClient(userUri, userOutboxPath, internalOpsKey));
        clients.put("dc-content", new HttpOutboxNodeClient(contentUri, "/internal/ops/outbox/status", internalOpsKey));
        clients.put("dc-comment", new HttpOutboxNodeClient(commentUri, "/internal/ops/outbox/status", internalOpsKey));
        clients.put("dc-interaction", new HttpOutboxNodeClient(
                interactionUri, interactionOutboxPath, internalOpsKey));
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.fleetTimeoutMillis = Math.max(100, fleetTimeoutMillis);
        this.snapshotPermits = new Semaphore(Math.max(1, maxConcurrentSnapshots));
    }

    OutboxFleetHealthService(Map<String, OutboxNodeClient> clients, ExecutorService executor) {
        this(clients, executor, 2_000, 2);
    }

    OutboxFleetHealthService(Map<String, OutboxNodeClient> clients, ExecutorService executor,
                             long fleetTimeoutMillis, int maxConcurrentSnapshots) {
        this.clients = new LinkedHashMap<>(clients);
        this.executor = executor;
        this.fleetTimeoutMillis = Math.max(1, fleetTimeoutMillis);
        this.snapshotPermits = new Semaphore(Math.max(1, maxConcurrentSnapshots));
    }

    public OutboxFleetSnapshot snapshot() {
        if (!snapshotPermits.tryAcquire()) {
            log.warn("Outbox fleet bulkhead is full");
            return unavailableSnapshot();
        }
        try {
            return collectSnapshot();
        } finally {
            snapshotPermits.release();
        }
    }

    private OutboxFleetSnapshot collectSnapshot() {
        List<Map.Entry<String, OutboxNodeClient>> entries = new ArrayList<>(clients.entrySet());
        List<java.util.concurrent.Callable<OutboxStatusView>> calls = entries.stream()
                .map(entry -> (java.util.concurrent.Callable<OutboxStatusView>) () -> {
                    OutboxStatusView status = entry.getValue().fetch();
                    if (status == null || !entry.getKey().equals(status.service())) {
                        throw new IllegalStateException("Outbox source identity mismatch");
                    }
                    return status;
                })
                .toList();
        List<Future<OutboxStatusView>> futures;
        try {
            futures = executor.invokeAll(calls, fleetTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            futures = List.of();
        }

        List<OutboxSourceView> sources = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        long backlog = 0;
        long retryable = 0;
        long dead = 0;
        long blocked = 0;
        for (int index = 0; index < entries.size(); index++) {
            String service = entries.get(index).getKey();
            Future<OutboxStatusView> future = index < futures.size() ? futures.get(index) : null;
            try {
                if (future == null || future.isCancelled()) throw new IllegalStateException("Outbox source timed out");
                OutboxStatusView status = future.get();
                backlog += status.backlog();
                retryable += status.retryable();
                dead += status.dead();
                blocked += status.blockedByDead();
                sources.add(source(status));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                unavailable.add(service);
                sources.add(unavailableSource(service));
            } catch (ExecutionException | RuntimeException ex) {
                unavailable.add(service);
                sources.add(unavailableSource(service));
                Throwable failure = ex instanceof ExecutionException && ex.getCause() != null
                        ? ex.getCause() : ex;
                log.warn("Outbox fleet source unavailable service={} failure={} message={}",
                        service, failure.getClass().getSimpleName(), failure.getMessage());
            }
        }

        boolean complete = unavailable.isEmpty();
        String reason = !complete ? "OUTBOX_SOURCE_UNAVAILABLE" : dead > 0 ? "OUTBOX_DEAD" : null;
        return new OutboxFleetSnapshot(
                "FLEET",
                complete,
                backlog,
                retryable,
                dead,
                blocked,
                List.copyOf(sources),
                List.copyOf(unavailable),
                !complete || dead > 0,
                reason);
    }

    public OutboxFleetSnapshot unavailableSnapshot() {
        return OutboxFleetSnapshot.unavailable(new ArrayList<>(clients.keySet()));
    }

    private OutboxSourceView source(OutboxStatusView status) {
        return OutboxSourceView.available(status.service(), status.backlog(), status.retryable(),
                status.dead(), status.blockedByDead());
    }

    private OutboxSourceView unavailableSource(String service) {
        return OutboxSourceView.unavailable(service);
    }

    @PreDestroy
    void close() {
        executor.close();
    }

    interface OutboxNodeClient {
        OutboxStatusView fetch();
    }

    static final class HttpOutboxNodeClient implements OutboxNodeClient {
        private final RestClient client;
        private final String statusPath;
        private final String internalOpsKey;

        HttpOutboxNodeClient(String baseUri, String statusPath, String internalOpsKey) {
            this.client = BoundedRestClientFactory.create(baseUri);
            this.statusPath = statusPath;
            this.internalOpsKey = internalOpsKey;
        }

        @Override
        public OutboxStatusView fetch() {
            OutboxStatusView body = client.get().uri(statusPath)
                    .header("X-Internal-Ops-Key", internalOpsKey)
                    .retrieve()
                    .body(OutboxStatusView.class);
            if (body == null) throw new IllegalStateException("Outbox source returned no body");
            return body;
        }
    }
}
