package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.DocumentService;
import com.yizhaoqi.smartpai.service.VectorizationUsageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentControllerReindexTest {
    private final DocumentController controller = new DocumentController();
    private final DocumentService service = mock(DocumentService.class);
    private final FileUploadRepository files = mock(FileUploadRepository.class);

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(controller, "documentService", service);
        ReflectionTestUtils.setField(controller, "fileUploadRepository", files);
    }

    @Test
    void ownerGetsTheirOwnRecordAndQueuedWorkDoesNotClaimCompletion() {
        when(files.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("shared", "owner"))
                .thenReturn(Optional.of(file("owner")));
        when(service.reindexDocument("shared", "owner"))
                .thenReturn(new VectorizationUsageResult(0, 0, "knowledge:queued"));

        var response = controller.reindexDocument("shared", "owner", "USER");
        assertEquals(202, response.getStatusCode().value());
        var body = (Map<?, ?>) response.getBody();
        var data = (Map<?, ?>) body.get("data");
        assertEquals("QUEUED", data.get("status"));
        assertNull(data.get("actualEmbeddingTokens"));
        assertNull(data.get("actualChunkCount"));
        verify(files, never()).findFirstByFileMd5OrderByCreatedAtDesc(anyString());
    }

    @Test
    void adminReindexesTheAuthorizedOwnersRecordAndLegacyCompletionStays200() {
        when(files.findFirstByFileMd5OrderByCreatedAtDesc("shared"))
                .thenReturn(Optional.of(file("owner")));
        when(service.reindexDocument("shared", "owner"))
                .thenReturn(new VectorizationUsageResult(20, 2, "legacy-model"));
        var response = controller.reindexDocument("shared", "admin", "ADMIN");
        assertEquals(200, response.getStatusCode().value());
        var data = (Map<?, ?>) ((Map<?, ?>) response.getBody()).get("data");
        assertEquals("COMPLETED", data.get("status"));
        assertEquals(2, data.get("actualChunkCount"));
        verify(service).reindexDocument("shared", "owner");
    }

    @Test
    void unrelatedUserCannotReindexAnotherOwnersFile() {
        when(files.findFirstByFileMd5OrderByCreatedAtDesc("shared"))
                .thenReturn(Optional.of(file("owner")));
        assertEquals(403, controller.reindexDocument("shared", "outsider", "USER").getStatusCode().value());
        verifyNoInteractions(service);
    }

    private FileUpload file(String owner) {
        FileUpload file = new FileUpload();
        file.setUserId(owner);
        file.setFileMd5("shared");
        file.setFileName("source.txt");
        return file;
    }
}
