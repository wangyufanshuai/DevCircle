package io.github.devcircle.admin.application;

import io.github.devcircle.common.event.OutboxStatusView;
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
import java.util.concurrent.TimeUnit;

@Service
public class OutboxFleetHealthService {
    private static final Logger log = LoggerFactory.getLogger(OutboxFleetHealthService.class);
    private static final long FLEET_TIMEOUT_MS = 2_500;

    private final Map<String, OutboxNodeClient> clients;
    private final ExecutorService executor;

    @Autowired
    public OutboxFleetHealthService(
            @Value("${devcircle.internal.auth-uri:http://localhost:8101}") String authUri,
            @Value("${devcircle.internal.user-uri:http://localhost:8102}") String userUri,
            @Value("${devcircle.internal.content-uri:http://localhost:8103}") String contentUri,
            @Value("${devcircle.internal.comment-uri:http://localhost:8104}") String commentUri,
            @Value("${devcircle.internal.interaction-uri:http://localhost:8105}") String interactionUri,
            @Value("${devcircle.internal.ops-key:local-development-internal-ops-key}") String internalOpsKey) {
        this.clients = new LinkedHashMap<>();
        clients.put("dc-auth", new HttpOutboxNodeClient(authUri, internalOpsKey));
        clients.put("dc-user", new HttpOutboxNodeClient(userUri, internalOpsKey));
        clients.put("dc-content", new HttpOutboxNodeClient(contentUri, internalOpsKey));
        clients.put("dc-comment", new HttpOutboxNodeClient(commentUri, internalOpsKey));
        clients.put("dc-interaction", new HttpOutboxNodeClient(interactionUri, internalOpsKey));
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    OutboxFleetHealthService(Map<String, OutboxNodeClient> clients, ExecutorService executor) {
        this.clients = new LinkedHashMap<>(clients);
        this.executor = executor;
    }

    public Map<String, Object> enrich(Map<String, Object> base, String reasonKey) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        Map<String, Object> fleet = snapshot();
        boolean baseDegraded = Boolean.TRUE.equals(base.get("degraded"));
        boolean fleetDegraded = Boolean.TRUE.equals(fleet.get("degraded"));
        Object existingReason = base.get(reasonKey);
        boolean adminDatabaseUnavailable = "DOWN".equals(base.get("database"))
                || "DATABASE_UNAVAILABLE".equals(existingReason)
                || "ADMIN_DATABASE_UNAVAILABLE".equals(existingReason)
                || (baseDegraded && "HEALTH_CHECK".equals(base.get("source")) && existingReason == null);
        result.putAll(fleet);
        result.put("degraded", baseDegraded || fleetDegraded);
        if (adminDatabaseUnavailable) {
            result.put(reasonKey, existingReason == null ? "ADMIN_DATABASE_UNAVAILABLE" : existingReason);
        } else if (fleetDegraded) {
            result.put(reasonKey, fleet.get("outboxDegradationReason"));
        } else if (baseDegraded && existingReason != null) {
            result.put(reasonKey, existingReason);
        } else {
            result.remove(reasonKey);
        }
        return Map.copyOf(result);
    }

    Map<String, Object> snapshot() {
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
            futures = executor.invokeAll(calls, FLEET_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            futures = List.of();
        }

        List<Map<String, Object>> sources = new ArrayList<>();
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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outboxScope", "FLEET");
        result.put("outboxComplete", complete);
        result.put("outboxBacklog", backlog);
        result.put("outboxRetryable", retryable);
        result.put("outboxDead", dead);
        result.put("outboxBlockedByDead", blocked);
        result.put("outboxSources", List.copyOf(sources));
        result.put("unavailableOutboxSources", List.copyOf(unavailable));
        result.put("degraded", !complete || dead > 0);
        if (!complete) result.put("outboxDegradationReason", "OUTBOX_SOURCE_UNAVAILABLE");
        else if (dead > 0) result.put("outboxDegradationReason", "OUTBOX_DEAD");
        return Map.copyOf(result);
    }

    private Map<String, Object> source(OutboxStatusView status) {
        return Map.of(
                "service", status.service(),
                "status", "UP",
                "backlog", status.backlog(),
                "retryable", status.retryable(),
                "dead", status.dead(),
                "blockedByDead", status.blockedByDead());
    }

    private Map<String, Object> unavailableSource(String service) {
        return Map.of("service", service, "status", "UNAVAILABLE");
    }

    @PreDestroy
    void close() {
        executor.close();
    }

    interface OutboxNodeClient {
        OutboxStatusView fetch();
    }

    private static final class HttpOutboxNodeClient implements OutboxNodeClient {
        private final RestClient client;
        private final String internalOpsKey;

        private HttpOutboxNodeClient(String baseUri, String internalOpsKey) {
            this.client = BoundedRestClientFactory.create(baseUri);
            this.internalOpsKey = internalOpsKey;
        }

        @Override
        public OutboxStatusView fetch() {
            OutboxStatusView body = client.get().uri("/internal/ops/outbox/status")
                    .header("X-Internal-Ops-Key", internalOpsKey)
                    .retrieve()
                    .body(OutboxStatusView.class);
            if (body == null) throw new IllegalStateException("Outbox source returned no body");
            return body;
        }
    }
}
