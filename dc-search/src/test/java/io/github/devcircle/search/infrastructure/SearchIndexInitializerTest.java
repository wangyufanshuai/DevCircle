package io.github.devcircle.search.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchIndexInitializerTest {
    @Test
    void existingIndexIsLeftUntouched() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IndexOperations index = mock(IndexOperations.class);
        when(operations.indexOps(PostSearchDocument.class)).thenReturn(index);
        when(index.exists()).thenReturn(true);

        new SearchIndexInitializer(operations).run(null);

        verify(index, never()).createWithMapping();
    }

    @Test
    void concurrentIndexCreationIsAcceptedAfterTheIndexAppears() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IndexOperations index = mock(IndexOperations.class);
        when(operations.indexOps(PostSearchDocument.class)).thenReturn(index);
        when(index.exists()).thenReturn(false, true);
        doThrow(new IllegalStateException("resource_already_exists_exception"))
                .when(index).createWithMapping();

        new SearchIndexInitializer(operations).run(null);

        var order = inOrder(index);
        order.verify(index).exists();
        order.verify(index).createWithMapping();
        order.verify(index).exists();
    }

    @Test
    void creationFailureIsNotHiddenWhenTheIndexStillDoesNotExist() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IndexOperations index = mock(IndexOperations.class);
        IllegalStateException failure = new IllegalStateException("cluster unavailable");
        when(operations.indexOps(PostSearchDocument.class)).thenReturn(index);
        when(index.exists()).thenReturn(false);
        doThrow(failure).when(index).createWithMapping();

        assertThatThrownBy(() -> new SearchIndexInitializer(operations).run(null))
                .isSameAs(failure);
    }
}
