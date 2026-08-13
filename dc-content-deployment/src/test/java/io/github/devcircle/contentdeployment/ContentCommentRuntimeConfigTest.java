package io.github.devcircle.contentdeployment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ContentCommentRuntimeConfigTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ContentCommentRuntimeConfig.class);

    @Test
    void disabledByDefaultCreatesNoRuntimeGuard() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("unsupportedCommentRuntimeGuard");
        });
    }

    @Test
    void enabledFailsClosedUntilIsolatedPersistenceExists() {
        contextRunner.withPropertyValues("devcircle.comment-runtime.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage(ContentCommentRuntimeConfig.UNSUPPORTED_MESSAGE);
                });
    }
}
