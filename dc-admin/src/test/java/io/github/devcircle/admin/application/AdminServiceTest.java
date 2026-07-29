package io.github.devcircle.admin.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminServiceTest {
    @Test
    void moderationWriteNeverReturnsFakeSuccessWithoutDatabase() {
        assertThatThrownBy(() -> new AdminService().approve(1002L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
    }
}
