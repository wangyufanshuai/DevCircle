package io.github.devcircle.identity;

import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "devcircle.identity.auth-datasource.url=jdbc:h2:mem:identity-embedded-auth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "devcircle.identity.auth-datasource.username=sa",
        "devcircle.identity.auth-datasource.password=",
        "spring.flyway.enabled=true",
        "devcircle.user-runtime.flyway-enabled=false",
        "devcircle.interaction-runtime.flyway-enabled=false",
        "devcircle.notification-runtime.flyway-enabled=false",
        "devcircle.user.profile-backfill.enabled=false",
        "devcircle.outbox.enabled=false",
        "devcircle.kafka.topics.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "management.tracing.enabled=false"
})
@AutoConfigureMockMvc
@Import(IdentityEmbeddedAuthWriteTest.H2AuthMigrationConfig.class)
class IdentityEmbeddedAuthWriteTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void identityRunsAuthMigrationsAndPersistsARegistrationLocally() throws Exception {
        assertThat(count("SELECT COUNT(*) FROM flyway_schema_history WHERE success = true AND version IS NOT NULL"))
                .isEqualTo(8);

        var registration = mockMvc.perform(post("/api/auth/register")
                        .header("Idempotency-Key", "identity-register-embedded-001")
                        .header("X-Trace-Id", "identity-register-trace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "embedded-reader@example.com",
                                  "password": "correct-password-123",
                                  "displayName": "Embedded Reader"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.displayName").value("Embedded Reader"))
                .andReturn();

        assertThat(registration.getResponse().getHeaders("Set-Cookie"))
                .anySatisfy(cookie -> assertThat(cookie).startsWith("dc_refresh="))
                .anySatisfy(cookie -> assertThat(cookie).startsWith("dc_csrf="));

        assertThat(count("SELECT COUNT(*) FROM user_account WHERE email = 'embedded-reader@example.com'"))
                .isOne();
        assertThat(count("""
                SELECT COUNT(*) FROM user_role ur
                JOIN user_account ua ON ua.id = ur.user_id
                JOIN role r ON r.id = ur.role_id
                WHERE ua.email = 'embedded-reader@example.com' AND r.code = 'USER'
                """))
                .isOne();
        assertThat(count("SELECT COUNT(*) FROM refresh_token")).isOne();
        assertThat(count("SELECT COUNT(*) FROM auth_idempotency_record WHERE status = 'COMPLETED'"))
                .isOne();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action = 'REGISTER'"))
                .isOne();
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE topic = 'user.registered'"))
                .isOne();
    }

    private long count(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class H2AuthMigrationConfig {
        @Bean
        FlywayConfigurationCustomizer h2AuthMigrationResourceProvider() {
            return configuration -> configuration.resourceProvider(
                    new H2AuthMigrationResourceProvider(IdentityEmbeddedAuthWriteTest.class.getClassLoader()));
        }
    }

    /**
     * Runs the production migration resources themselves, changing only three MySQL multi-clause
     * ALTER statements into equivalent H2 statements. Every replacement is fail-fast so migration
     * changes cannot silently leave this proof on a stale test schema.
     */
    static final class H2AuthMigrationResourceProvider implements ResourceProvider {
        private static final List<String> MIGRATIONS = List.of(
                "V1__auth_schema.sql",
                "V2__rbac_refresh_audit.sql",
                "V3__secure_identity.sql",
                "V4__reliable_outbox.sql",
                "V5__auth_request_idempotency.sql",
                "V6__remove_idempotency_response_payload.sql",
                "V7__outbox_aggregate_ordering.sql",
                "V8__widen_legacy_username.sql"
        );
        private final ClassLoader classLoader;

        H2AuthMigrationResourceProvider(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        public LoadableResource getResource(String name) {
            String filename = name.substring(name.lastIndexOf('/') + 1);
            return MIGRATIONS.contains(filename) ? new H2AuthMigrationResource(filename, classLoader) : null;
        }

        @Override
        public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
            return MIGRATIONS.stream()
                    .filter(name -> name.startsWith(prefix))
                    .filter(name -> Arrays.stream(suffixes).anyMatch(name::endsWith))
                    .map(name -> (LoadableResource) new H2AuthMigrationResource(name, classLoader))
                    .toList();
        }
    }

    static final class H2AuthMigrationResource extends LoadableResource {
        private static final String LOCATION = "db/migration/";
        private final String filename;
        private final ClassLoader classLoader;

        H2AuthMigrationResource(String filename, ClassLoader classLoader) {
            this.filename = filename;
            this.classLoader = classLoader;
        }

        @Override
        public Reader read() {
            try (InputStream input = classLoader.getResourceAsStream(LOCATION + filename)) {
                if (input == null) {
                    throw new IllegalStateException("Missing auth migration " + filename);
                }
                String source = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n");
                return new StringReader(toH2(source));
            } catch (IOException error) {
                throw new IllegalStateException("Cannot read auth migration " + filename, error);
            }
        }

        private String toH2(String source) {
            if (filename.equals("V3__secure_identity.sql")) {
                source = replaceRequired(source, """
                        ALTER TABLE refresh_token
                            ADD COLUMN csrf_hash CHAR(64) NULL AFTER token_hash,
                            ADD COLUMN family_id CHAR(36) NULL AFTER csrf_hash,
                            ADD COLUMN used_at TIMESTAMP NULL AFTER revoked,
                            ADD COLUMN replaced_by_hash CHAR(64) NULL AFTER used_at,
                            ADD INDEX idx_refresh_family (family_id);
                        """, """
                        ALTER TABLE refresh_token ADD COLUMN csrf_hash CHAR(64) NULL AFTER token_hash;
                        ALTER TABLE refresh_token ADD COLUMN family_id CHAR(36) NULL AFTER csrf_hash;
                        ALTER TABLE refresh_token ADD COLUMN used_at TIMESTAMP NULL AFTER revoked;
                        ALTER TABLE refresh_token ADD COLUMN replaced_by_hash CHAR(64) NULL AFTER used_at;
                        CREATE INDEX idx_refresh_family ON refresh_token(family_id);
                        """);
                source = replaceRequired(source,
                        "SHA2(CONCAT(token_hash, '-legacy-csrf'), 256)",
                        "LOWER(RAWTOHEX(HASH('SHA-256', STRINGTOUTF8(CONCAT(token_hash, '-legacy-csrf')))))");
                source = replaceRequired(source, """
                        ALTER TABLE refresh_token
                            MODIFY csrf_hash CHAR(64) NOT NULL,
                            MODIFY family_id CHAR(36) NOT NULL;
                        """, """
                        ALTER TABLE refresh_token MODIFY csrf_hash CHAR(64) NOT NULL;
                        ALTER TABLE refresh_token MODIFY family_id CHAR(36) NOT NULL;
                        """);
            } else if (filename.equals("V4__reliable_outbox.sql")) {
                source = replaceRequired(source, """
                        ALTER TABLE outbox_event
                            ADD COLUMN attempts INT NOT NULL DEFAULT 0 AFTER status,
                            ADD COLUMN next_attempt_at TIMESTAMP NULL AFTER attempts,
                            ADD COLUMN locked_at TIMESTAMP NULL AFTER next_attempt_at,
                            ADD COLUMN locked_by VARCHAR(64) NULL AFTER locked_at,
                            ADD COLUMN last_error VARCHAR(1000) NULL AFTER locked_by,
                            ADD INDEX idx_outbox_ready (status, next_attempt_at, created_at);
                        """, """
                        ALTER TABLE outbox_event ADD COLUMN attempts INT NOT NULL DEFAULT 0 AFTER status;
                        ALTER TABLE outbox_event ADD COLUMN next_attempt_at TIMESTAMP NULL AFTER attempts;
                        ALTER TABLE outbox_event ADD COLUMN locked_at TIMESTAMP NULL AFTER next_attempt_at;
                        ALTER TABLE outbox_event ADD COLUMN locked_by VARCHAR(64) NULL AFTER locked_at;
                        ALTER TABLE outbox_event ADD COLUMN last_error VARCHAR(1000) NULL AFTER locked_by;
                        CREATE INDEX idx_outbox_ready ON outbox_event(status, next_attempt_at, created_at);
                        """);
            } else if (filename.equals("V7__outbox_aggregate_ordering.sql")) {
                source = replaceRequired(source,
                        "ALTER TABLE outbox_event\n    ADD INDEX idx_outbox_aggregate_order (topic, aggregate_id, id, status);",
                        "CREATE INDEX idx_outbox_aggregate_order ON outbox_event(topic, aggregate_id, id, status);");
            }
            return source;
        }

        private String replaceRequired(String source, String mysql, String h2) {
            if (!source.contains(mysql)) {
                throw new IllegalStateException(
                        "H2 compatibility rule no longer matches auth migration " + filename);
            }
            return source.replace(mysql, h2);
        }

        @Override
        public String getAbsolutePath() {
            return "classpath:" + LOCATION + filename;
        }

        @Override
        public String getAbsolutePathOnDisk() {
            return null;
        }

        @Override
        public String getFilename() {
            return filename;
        }

        @Override
        public String getRelativePath() {
            return LOCATION + filename;
        }
    }
}
