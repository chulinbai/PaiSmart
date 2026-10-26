package com.yizhaoqi.smartpai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "knowledge_multimodal_items",
        indexes = {
                @Index(name = "idx_kmi_file_md5", columnList = "file_md5"),
                @Index(name = "idx_kmi_item_id", columnList = "item_id", unique = true),
                @Index(name = "idx_kmi_knowledge_type", columnList = "knowledge_type"),
                @Index(name = "idx_kmi_owner", columnList = "user_id"),
                @Index(name = "idx_kmi_org_tag", columnList = "org_tag")
        }
)
public class KnowledgeMultimodalItem {

    public static final String TYPE_TEXT_CHUNK = "TEXT_CHUNK";
    public static final String TYPE_DOCUMENT_IMAGE = "DOCUMENT_IMAGE";
    public static final String TYPE_STANDALONE_IMAGE = "STANDALONE_IMAGE";

    public static final String SOURCE_MINIO_OBJECT = "MINIO_OBJECT";
    public static final String SOURCE_DOCUMENT_EMBEDDED = "DOCUMENT_EMBEDDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", length = 128, nullable = false, unique = true)
    private String itemId;

    @Column(name = "file_md5", length = 32, nullable = false)
    private String fileMd5;

    @Column(name = "file_name", length = 512)
    private String fileName;

    @Column(name = "knowledge_type", length = 32, nullable = false)
    private String knowledgeType;

    @Lob
    @Column(name = "search_text", columnDefinition = "LONGTEXT")
    private String searchText;

    @Column(name = "model_version", length = 256)
    private String modelVersion;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "org_tag", length = 50)
    private String orgTag;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic;

    @Column(name = "chunk_id")
    private Integer chunkId;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "anchor_text", length = 512)
    private String anchorText;

    @Lob
    @Column(name = "text_content", columnDefinition = "LONGTEXT")
    private String textContent;

    @Lob
    @Column(name = "image_text", columnDefinition = "LONGTEXT")
    private String imageText;

    @Column(name = "image_source_type", length = 32)
    private String imageSourceType;

    @Column(name = "image_url", length = 2048)
    private String imageUrl;

    @Column(name = "bucket_name", length = 128)
    private String bucketName;

    @Column(name = "object_name", length = 1024)
    private String objectName;

    @Column(name = "embedded_image_id", length = 160)
    private String embeddedImageId;

    @Column(name = "image_sequence")
    private Integer imageSequence;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
