package io.github.devcircle.contentdeployment;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ContentCommentRuntimeConfigTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ContentCommentRuntimeConfig.class);

    @Test
    void disabledByDefaultCreatesNoCommentPersistence() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("commentDataSource");
            assertThat(context).doesNotHaveBean("commentTransactionManager");
            assertThat(context).doesNotHaveBean("commentFlyway");
            assertThat(context).doesNotHaveBean("commentRepository");
        });
    }

    @Test
    void datasourcePropertiesRejectUnsafePoolConfiguration() {
        ContentCommentRuntimeConfig.CommentDataSourceProperties properties =
                new ContentCommentRuntimeConfig.CommentDataSourceProperties();
        properties.setUrl("jdbc:h2:mem:comment");
        properties.setUsername("sa");
        properties.setMaximumPoolSize(1);
        properties.setMinimumIdle(2);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(properties::validate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimumIdle");
    }

    @Test
    void aggregatePoolGuardRejectsBudgetsAboveTheConfiguredCeiling() {
        HikariDataSource content = pool(6);
        HikariDataSource comment = pool(3);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("devcircle.content.aggregate-db-pool-ceiling", "8");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new ContentCommentRuntimeConfig().commentAggregatePoolBudgetGuard(
                        content, comment, environment)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("9 > 8");
        content.close();
        comment.close();
    }

    private HikariDataSource pool(int maximumPoolSize) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setMaximumPoolSize(maximumPoolSize);
        return dataSource;
    }
}
