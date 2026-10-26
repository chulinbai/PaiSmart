package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.ContextArtifact;
import com.yizhaoqi.smartpai.repository.ContextArtifactRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ContextArtifactService {
    public static final String BUCKET = "uploads";
    private final MinioClient minioClient;
    private final ContextArtifactRepository repository;

    public ContextArtifactService(MinioClient minioClient, ContextArtifactRepository repository) {
        this.minioClient = minioClient;
        this.repository = repository;
    }

    @Transactional
    public ContextArtifact storeText(Long userId, String workspaceId, String content, String mimeType, boolean pinned) {
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            String id = UUID.randomUUID().toString();
            String objectKey = "context-artifacts/" + workspaceId + "/" + id + ".txt";
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(BUCKET).object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(mimeType).build());

            ContextArtifact artifact = new ContextArtifact();
            artifact.setId(id);
            artifact.setOwnerUserId(userId);
            artifact.setWorkspaceId(workspaceId);
            artifact.setBucket(BUCKET);
            artifact.setObjectKey(objectKey);
            artifact.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            artifact.setSize(bytes.length);
            artifact.setMimeType(mimeType);
            artifact.setPreview(preview(content));
            artifact.setPinned(pinned);
            artifact.setExpiresAt(pinned ? null : LocalDateTime.now().plusDays(30));
            return repository.save(artifact);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to store context artifact", e);
        }
    }

    public ArtifactDownload openForOwner(String artifactId, Long userId) {
        ContextArtifact artifact = repository.findByIdAndOwnerUserId(artifactId, userId)
                .orElseThrow(() -> new CustomException("Artifact not found", HttpStatus.NOT_FOUND));
        try {
            InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(artifact.getBucket()).object(artifact.getObjectKey()).build());
            return new ArtifactDownload(artifact, stream);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read context artifact", e);
        }
    }

    private String preview(String content) {
        if (content.length() <= 1600) return content;
        return content.substring(0, 800) + "\n...[artifact content omitted]...\n" + content.substring(content.length() - 800);
    }

    public record ArtifactDownload(ContextArtifact artifact, InputStream stream) {}
}
