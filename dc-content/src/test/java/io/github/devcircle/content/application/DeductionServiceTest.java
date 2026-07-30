package io.github.devcircle.content.application;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeductionServiceTest {
    @Test
    void failsClearlyWhenDatabaseIsUnavailable() {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        when(jdbcClient.sql(anyString())).thenThrow(new DataAccessResourceFailureException("offline"));
        DeductionService service = new DeductionService(jdbcClient);

        assertThatThrownBy(() -> service.submitAnswer(2L, 1001L,
                "凶手利用钟楼暗层和怀表制造时间差", "deduction-test-1"))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void doesNotReturnSyntheticSuccessWhenDatabaseIsUnavailable() {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        when(jdbcClient.sql(anyString())).thenThrow(new DataAccessResourceFailureException("offline"));
        DeductionService service = new DeductionService(jdbcClient);

        assertThatThrownBy(() -> service.submitAnswer(2L, 1001L,
                "答案：凶手躲在钟楼隔层，并用记录伪造时间差。", "deduction-test-2"))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void statsRequireAuthenticationAndNeverFallBackToSyntheticValues() {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        DeductionService service = new DeductionService(jdbcClient);
        assertThatThrownBy(() -> service.stats(null)).hasMessageContaining("请先登录");

        when(jdbcClient.sql(anyString())).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThatThrownBy(() -> service.stats(2L)).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
