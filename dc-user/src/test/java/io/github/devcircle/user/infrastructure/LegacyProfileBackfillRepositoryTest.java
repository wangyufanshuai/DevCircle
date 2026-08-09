package io.github.devcircle.user.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyProfileBackfillRepositoryTest {
    @Test
    void copiesOnlyMissingActiveAccountsAndCanResumeInBatches() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:legacy-profile-backfill;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("CREATE SCHEMA dc_auth").update();
        jdbc.sql("CREATE TABLE dc_auth.user_account (id BIGINT PRIMARY KEY, display_name VARCHAR(64), status VARCHAR(32))").update();
        jdbc.sql("CREATE TABLE user_profile (user_id BIGINT PRIMARY KEY, username VARCHAR(64))").update();
        jdbc.sql("INSERT INTO dc_auth.user_account VALUES (1, '旧显示名', 'ACTIVE'), (2, '新读者', 'ACTIVE'), (3, '已封禁', 'BANNED')").update();
        jdbc.sql("INSERT INTO user_profile VALUES (1, '自定义资料名')").update();

        LegacyProfileBackfillRepository repository = new LegacyProfileBackfillRepository(jdbc, "dc_auth");

        assertThat(repository.copyMissingProfiles(1)).isEqualTo(1);
        assertThat(repository.copyMissingProfiles(1)).isZero();
        assertThat(jdbc.sql("SELECT username FROM user_profile WHERE user_id = 1").query(String.class).single())
                .isEqualTo("自定义资料名");
        assertThat(jdbc.sql("SELECT username FROM user_profile WHERE user_id = 2").query(String.class).single())
                .isEqualTo("新读者");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM user_profile WHERE user_id = 3").query(Integer.class).single())
                .isZero();
    }
}
