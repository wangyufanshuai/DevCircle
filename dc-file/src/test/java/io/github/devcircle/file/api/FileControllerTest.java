package io.github.devcircle.file.api;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.file.infrastructure.MediaRepository;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class FileControllerTest {
    private static final Instant FUTURE = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void intentRejectsUnsupportedExtensionBeforeTouchingObjectStorage() {
        S3Client s3 = mock(S3Client.class);
        MediaRepository repository = mock(MediaRepository.class);
        FileController controller = controller(s3, mock(S3Presigner.class), repository);

        assertRejected(() -> controller.createIntent(7L, "intent-key",
                new UploadIntentRequest("evidence.exe", "image/png", 64, 32, 32, null)));

        verifyNoInteractions(s3, repository);
    }

    @Test
    void completionRejectsExpiredIntentWithoutHeadRequest() {
        S3Client s3 = mock(S3Client.class);
        MediaRepository repository = mock(MediaRepository.class);
        FileController controller = controller(s3, mock(S3Presigner.class), repository);
        when(repository.requireOwned(7L, 12L)).thenReturn(asset(12L, Instant.parse("2020-01-01T00:00:00Z"), "UPLOADING"));

        assertThatThrownBy(() -> controller.complete(7L, "complete-key", 12L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.UPLOAD_REJECTED));

        verify(s3, never()).headObject(any(HeadObjectRequest.class));
        verify(repository, never()).complete(any(), any(), any(), anyLong());
    }

    @Test
    void completionDeletesObjectWhenMagicHeaderDoesNotMatchDeclaredMime() {
        S3Client s3 = mock(S3Client.class);
        MediaRepository repository = mock(MediaRepository.class);
        FileController controller = controller(s3, mock(S3Presigner.class), repository);
        when(repository.requireOwned(7L, 12L)).thenReturn(asset(12L, FUTURE, "UPLOADING"));
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(8L).contentType("image/png").build());
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(), new byte[]{0, 1, 2, 3, 4, 5, 6, 7}));

        assertRejected(() -> controller.complete(7L, "complete-key", 12L));

        verify(s3).deleteObject(any(DeleteObjectRequest.class));
        verify(repository, never()).complete(any(), any(), any(), anyLong());
    }

    @Test
    void completionChecksHeadAndMagicBeforePersistingCompletedStatus() {
        S3Client s3 = mock(S3Client.class);
        MediaRepository repository = mock(MediaRepository.class);
        FileController controller = controller(s3, mock(S3Presigner.class), repository);
        when(repository.requireOwned(7L, 12L)).thenReturn(asset(12L, FUTURE, "UPLOADING"));
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(8L).contentType("image/png").build());
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(), new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0, 0, 0, 0}));
        FileUploadResult expected = new FileUploadResult(12L, "private/12.png", "", "image/png", 8,
                32, 32, null, "PENDING_REVIEW");
        when(repository.complete(7L, 12L, "complete-key", 8L)).thenReturn(expected);

        var response = controller.complete(7L, "complete-key", 12L);

        assertThat(response.data()).isEqualTo(expected);
        verify(repository).complete(7L, 12L, "complete-key", 8L);
    }

    @Test
    void completedReplayReturnsPersistedResultWithoutRecheckingObjectStorage() {
        S3Client s3 = mock(S3Client.class);
        MediaRepository repository = mock(MediaRepository.class);
        FileController controller = controller(s3, mock(S3Presigner.class), repository);
        when(repository.requireOwned(7L, 12L)).thenReturn(asset(12L, FUTURE, "COMPLETED"));
        FileUploadResult expected = new FileUploadResult(12L, "private/12.png", "", "image/png", 8,
                32, 32, null, "PENDING_REVIEW");
        when(repository.complete(7L, 12L, "replay-key", 8L)).thenReturn(expected);

        var response = controller.complete(7L, "replay-key", 12L);

        assertThat(response.data()).isEqualTo(expected);
        verify(repository).complete(7L, 12L, "replay-key", 8L);
        verifyNoInteractions(s3);
    }

    private FileController controller(S3Client s3, S3Presigner presigner, MediaRepository repository) {
        return new FileController(s3, presigner, repository, "private", "http://minio:9000", 1024, 15);
    }

    private MediaRepository.AssetIntent asset(long id, Instant expiresAt, String uploadStatus) {
        return new MediaRepository.AssetIntent(id, 7L, "private/uploads/7/12.png", "internal/12.png",
                "image/png", 8, 32, 32, null, "PENDING_REVIEW", uploadStatus, expiresAt);
    }

    private void assertRejected(ThrowingAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.UPLOAD_REJECTED));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
