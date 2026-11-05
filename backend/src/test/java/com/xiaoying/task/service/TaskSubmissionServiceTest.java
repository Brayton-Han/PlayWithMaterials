package com.xiaoying.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.xiaoying.catalog.domain.Template;
import com.xiaoying.catalog.service.CatalogService;
import com.xiaoying.common.error.ApiException;
import com.xiaoying.common.error.ErrorCode;
import com.xiaoying.simulation.SimulationSettings;
import com.xiaoying.storage.FileStorageService;
import com.xiaoying.storage.ImageType;
import com.xiaoying.storage.StorageException;
import com.xiaoying.storage.StoredFile;
import com.xiaoying.storage.UploadBatch;
import com.xiaoying.task.domain.Task;
import com.xiaoying.task.domain.TaskView;
import com.xiaoying.task.mapper.TaskMapper;
import com.xiaoying.task.pipeline.TaskDispatcher;

/**
 * 事务提交之后的补偿顺序（architecture §5.1）：commit 文件失败、派发被拒绝、派发异常时撤销任务并删除文件；
 * 任务已无法撤销（已被领取）时保留文件。数据库与 HTTP 层的行为见 {@code TaskSubmissionIT}。
 */
class TaskSubmissionServiceTest {

    private static final long USER = 7L;
    private static final long TASK = 42L;

    CatalogService catalog = mock(CatalogService.class);
    FileStorageService storage = mock(FileStorageService.class);
    UploadBatch batch = mock(UploadBatch.class);
    TaskWriter writer = mock(TaskWriter.class);
    TaskMapper taskMapper = mock(TaskMapper.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    SimulationSettings simulation = mock(SimulationSettings.class);

    TaskSubmissionService service;

    @BeforeEach
    void setUp() {
        Template t = new Template();
        t.setId("creative-poster");
        t.setRequiredImageCount(1);
        t.setAcceptedMimeTypes(List.of("image/jpeg", "image/png"));
        t.setMaxFileSizeBytes(5 * 1024 * 1024);
        when(catalog.getTemplate("creative-poster")).thenReturn(t);
        when(storage.newUploadBatch()).thenReturn(batch);
        when(batch.stage(any(), eq(ImageType.JPEG)))
                .thenReturn(new StoredFile("uploads/2026/11/x.jpg", "/files/uploads/2026/11/x.jpg", ImageType.JPEG, 4));
        when(simulation.snapshotJson()).thenReturn("{}");
        Task task = new Task(USER, "creative-poster", "{}");
        task.setId(TASK);
        when(writer.create(eq(USER), eq("creative-poster"), anyString(), anyList()))
                .thenReturn(new TaskView(task, List.of()));
        when(taskMapper.deleteById(TASK)).thenReturn(1);
        service = new TaskSubmissionService(catalog, storage, writer, taskMapper, dispatcher, simulation);
    }

    @Test
    void happyPathCommitsFilesBeforeDispatching() {
        TaskView view = service.submit(USER, "creative-poster", files());

        assertThat(view.task().getId()).isEqualTo(TASK);
        InOrder order = inOrder(batch, writer, dispatcher);
        order.verify(batch).stage(any(), eq(ImageType.JPEG));
        order.verify(writer).create(eq(USER), eq("creative-poster"), eq("{}"), anyList());
        order.verify(batch).commit();
        order.verify(dispatcher).dispatch(TASK);
        verify(batch, never()).rollback();
        verify(taskMapper, never()).deleteById(anyLong());
    }

    @Test
    void validationFailureNeverOpensABatch() {
        assertThatThrownBy(() -> service.submit(USER, "creative-poster",
                List.of(new MockMultipartFile("files", "a.gif", "image/gif", "GIF89a....".getBytes()))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ASSET_VALIDATION_FAILED));
        verify(storage, never()).newUploadBatch();
    }

    @Test
    void writerFailureRollsBackStagedFilesAndNeverDispatches() {
        when(writer.create(anyLong(), anyString(), anyString(), anyList())).thenThrow(new IllegalStateException("db"));

        assertThatThrownBy(() -> service.submit(USER, "creative-poster", files()))
                .isInstanceOf(IllegalStateException.class);
        verify(batch).rollback();
        verify(batch, never()).commit();
        verify(dispatcher, never()).dispatch(anyLong());
    }

    @Test
    void commitFailureWithdrawsTaskThenDeletesFiles() {
        doThrow(new StorageException("disk", null)).when(batch).commit();

        assertThatThrownBy(() -> service.submit(USER, "creative-poster", files()))
                .isInstanceOf(StorageException.class);
        InOrder order = inOrder(taskMapper, batch);
        order.verify(taskMapper).deleteById(TASK);
        order.verify(batch).rollback();
        verify(dispatcher, never()).dispatch(anyLong());
    }

    @Test
    void rejectedDispatchWithdrawsTaskAndFilesAndMapsTo503() {
        doThrow(new TaskRejectedException("full")).when(dispatcher).dispatch(TASK);

        assertThatThrownBy(() -> service.submit(USER, "creative-poster", files()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.QUEUE_FULL));
        InOrder order = inOrder(taskMapper, batch);
        order.verify(taskMapper).deleteById(TASK);
        order.verify(batch).rollback();
    }

    @Test
    void unexpectedDispatchErrorAlsoWithdrawsAndPropagates() {
        doThrow(new IllegalStateException("boom")).when(dispatcher).dispatch(TASK);

        assertThatThrownBy(() -> service.submit(USER, "creative-poster", files()))
                .isInstanceOf(IllegalStateException.class);
        verify(taskMapper).deleteById(TASK);
        verify(batch).rollback();
    }

    @Test
    void filesAreKeptWhenTheTaskCannotBeWithdrawn() {
        doThrow(new IllegalStateException("boom")).when(dispatcher).dispatch(TASK);
        when(taskMapper.deleteById(TASK)).thenReturn(0); // 已被执行器领取，不再是 PENDING

        assertThatThrownBy(() -> service.submit(USER, "creative-poster", files()))
                .isInstanceOf(IllegalStateException.class);
        verify(batch, never()).rollback();
    }

    @Test
    void formattingHelpers() {
        assertThat(TaskSubmissionService.formatSize(5_242_880)).isEqualTo("5 MB");
        assertThat(TaskSubmissionService.formatSize(1_048_576)).isEqualTo("1 MB");
        assertThat(TaskSubmissionService.formatSize(1_572_864)).isEqualTo("1.5 MB");
        assertThat(TaskSubmissionService.formatSize(512_000)).isEqualTo("500 KB");
        assertThat(TaskSubmissionService.displayNames(Set.of(ImageType.WEBP))).isEqualTo("WebP");

        String longName = "😀".repeat(300);
        String cut = TaskSubmissionService.originalName(new MockMultipartFile("files", longName, "image/png", new byte[1]));
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(255);
        assertThat(TaskSubmissionService.originalName(new MockMultipartFile("files", null, "image/png", new byte[1])))
                .isEmpty();
    }

    private static List<MultipartFile> files() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        return List.of(new MockMultipartFile("files", "a.jpg", "image/jpeg", jpeg));
    }
}
