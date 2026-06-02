package io.github.devcircle.content.application;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeductionServiceTest {
    @Test
    void judgesByCaseSpecificAnswerKeywordsWhenDatabaseIsUnavailable() {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        when(jdbcClient.sql(anyString())).thenThrow(new DataAccessResourceFailureException("offline"));
        DeductionService service = new DeductionService(jdbcClient);

        var correct = service.submitAnswer(2L, 1001L, "凶手利用钟楼暗层和怀表制造时间差");
        var wrong = service.submitAnswer(2L, 1001L, "我认为是陌生人从窗户逃走");

        assertThat(correct.correct()).isTrue();
        assertThat(correct.points()).isEqualTo(20);
        assertThat(correct.explanation()).contains("关键");
        assertThat(wrong.correct()).isFalse();
    }

    @Test
    void normalizesChinesePunctuationAndAcceptsSynonyms() {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        when(jdbcClient.sql(anyString())).thenThrow(new DataAccessResourceFailureException("offline"));
        DeductionService service = new DeductionService(jdbcClient);

        var answer = service.submitAnswer(2L, 1001L, "答案：凶手躲在钟楼隔层，并用记录伪造时间差。");

        assertThat(answer.correct()).isTrue();
    }
}
