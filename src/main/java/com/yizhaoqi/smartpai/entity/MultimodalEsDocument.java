package com.yizhaoqi.smartpai.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class MultimodalEsDocument {

    private String id;
    private String itemId;
    private String fileMd5;
    private String fileName;
    private String knowledgeType;
    private String searchText;
    private float[] vector;
    private String modelVersion;
    private String userId;
    private String orgTag;
    @JsonProperty("isPublic")
    private boolean isPublic;

    private Integer chunkId;
    private Integer pageNumber;
    private String anchorText;
    private String textContent;

    private String imageText;
    private String imageSourceType;
    private String imageUrl;
    private String bucketName;
    private String objectName;
    private String embeddedImageId;
    private Integer imageSequence;
    private String contentType;
    private Integer width;
    private Integer height;
}
