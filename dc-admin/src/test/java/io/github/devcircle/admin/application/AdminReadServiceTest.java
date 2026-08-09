package io.github.devcircle.admin.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminReadServiceTest {
    @Test
    void readQueriesFailExplicitlyWhenTheDatabaseIsUnavailable() {
        AdminReadService service = new AdminReadService();

        assertThatThrownBy(service::pendingPosts)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code())
                                .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
    }
}
