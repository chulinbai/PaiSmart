package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.yizhaoqi.smartpai.client.MultiEmbeddingClient;
import com.yizhaoqi.smartpai.entity.MultimodalEsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.KnowledgeMultimodalItem;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MultimodalSearchService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.yizhaoqi.smartpai.service.knowledge.KnowledgeAccessService knowledgeAccess;

    private static final Logger logger = LoggerFactory.getLogger(MultimodalSearchService.class);

    private final ElasticsearchClient esClient;
    private final MultiEmbeddingClient multiEmbeddingClient;
    private final UserRepository userRepository;
    private final OrgTagCacheService orgTagCacheService;
    private final FileUploadRepository fileUploadRepository;
    private final UploadService uploadService;
    private final MultimodalElasticsearchService multimodalElasticsearchService;

    public MultimodalSearchService(ElasticsearchClient esClient,
                                   MultiEmbeddingClient multiEmbeddingClient,
                                   UserRepository userRepository,
                                   OrgTagCacheService orgTagCacheService,
                                   FileUploadRepository fileUploadRepository,
                                   UploadService uploadService,
                                   MultimodalElasticsearchService multimodalElasticsearchService) {
        this.esClient = esClient;
        this.multiEmbeddingClient = multiEmbeddingClient;
        this.userRepository = userRepository;
        this.orgTagCacheService = orgTagCacheService;
        this.fileUploadRepository = fileUploadRepository;
        this.uploadService = uploadService;
        this.multimodalElasticsearchService = multimodalElasticsearchService;
    }

    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        return searchWithPermission(query, null, userId, topK);
    }

    public List<SearchResult> searchWithPermission(String query, String image, String userId, int topK) {
        logger.debug("开始多模态带权限搜索，查询: {}, 用户ID: {}", query, userId);
        try {
            String normalizedQuery = query == null ? "" : query.trim();
            String normalizedImage = image == null ? "" : image.trim();
            if (normalizedQuery.isBlank() && normalizedImage.isBlank()) {
                return Collections.emptyList();
            }

            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            String userDbId = getUserDbId(userId);
            MultiEmbeddingClient.EmbeddingInput queryInput = normalizedImage.isBlank()
                    ? MultiEmbeddingClient.EmbeddingInput.text(normalizedQuery)
                    : MultiEmbeddingClient.EmbeddingInput.imageWithText(normalizedQuery, normalizedImage);
            List<Float> queryVector = embedToVectorList(queryInput, userId);

            if (queryVector == null) {
                logger.warn("多模态查询向量生成失败，降级为多模态文本检索");
                return normalizedQuery.isBlank()
                        ? Collections.emptyList()
                        : textOnlySearchWithPermission(normalizedQuery, userDbId, userEffectiveTags, topK);
            }

            int recallK = Math.max(topK, topK * 30);
            SearchResponse<MultimodalEsDocument> response = esClient.search(s -> {
                        s.index(multimodalElasticsearchService.indexName());
                        s.knn(kn -> kn
                                .field("vector")
                                .queryVector(queryVector)
                                .k(recallK)
                                .numCandidates(recallK)
                                .filter(f -> f.bool(bf -> appendPermissionShoulds(bf, userDbId, userEffectiveTags)))
                        );
                        s.query(q -> q.bool(b -> {
                            b.filter(f -> f.bool(bf -> appendPermissionShoulds(bf, userDbId, userEffectiveTags)));
                            if (!normalizedQuery.isBlank()) {
                                b.should(sh -> sh.match(m -> m.field("searchText").query(normalizedQuery)));
                            }
                            return b;
                        }));
                        if (!normalizedQuery.isBlank()) {
                            s.rescore(r -> r
                                    .windowSize(recallK)
                                    .query(rq -> rq
                                            .queryWeight(0.7d)
                                            .rescoreQueryWeight(0.6d)
                                            .query(rqq -> rqq.match(m -> m
                                                    .field("searchText")
                                                    .query(normalizedQuery)
                                                    .operator(Operator.And)
                                            ))
                                    )
                            );
                        }
                        s.size(topK);
                        return s;
                    },
                    MultimodalEsDocument.class
            );

            List<SearchResult> results = response.hits().hits().stream()
                    .map(hit -> toSearchResult(hit.source(), hit.score(), "MULTIMODAL_HYBRID"))
                    .filter(result -> currentPermission(result, userDbId, userEffectiveTags))
                    .toList();
            attachFileNamesAndImageUrls(results);
            return results;
        } catch (Exception e) {
            logger.error("多模态带权限搜索失败", e);
            try {
                String normalizedQuery = query == null ? "" : query.trim();
                return normalizedQuery.isBlank()
                        ? Collections.emptyList()
                        : textOnlySearchWithPermission(normalizedQuery, getUserDbId(userId), getUserEffectiveOrgTags(userId), topK);
            } catch (Exception fallbackError) {
                logger.error("多模态后备搜索也失败", fallbackError);
                return Collections.emptyList();
            }
        }
    }

    private co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder appendPermissionShoulds(
            co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder bf,
            String userDbId,
            List<String> userEffectiveTags) {
        bf.minimumShouldMatch("1");
        bf.should(s1 -> s1.term(t -> t.field("userId").value(userDbId)));
        bf.should(s2 -> s2.term(t -> t.field("isPublic").value(true)));
        bf.should(s3 -> {
            if (userEffectiveTags.isEmpty()) {
                return s3.matchNone(mn -> mn);
            }
            if (userEffectiveTags.size() == 1) {
                return s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0)));
            }
            return s3.bool(inner -> {
                userEffectiveTags.forEach(tag -> inner.should(sh -> sh.term(t -> t.field("orgTag").value(tag))));
                inner.minimumShouldMatch("1");
                return inner;
            });
        });
        return bf;
    }

    private List<SearchResult> textOnlySearchWithPermission(String query, String userDbId, List<String> userEffectiveTags, int topK) {
        try {
            SearchResponse<MultimodalEsDocument> response = esClient.search(s -> s
                            .index(multimodalElasticsearchService.indexName())
                            .query(q -> q.bool(b -> b
                                    .must(m -> m.match(ma -> ma.field("searchText").query(query)))
                                    .filter(f -> f.bool(bf -> appendPermissionShoulds(bf, userDbId, userEffectiveTags)))
                            ))
                            .minScore(0.3d)
                            .size(topK),
                    MultimodalEsDocument.class
            );

            List<SearchResult> results = response.hits().hits().stream()
                    .map(hit -> toSearchResult(hit.source(), hit.score(), "MULTIMODAL_TEXT_ONLY"))
                    .filter(result -> currentPermission(result, userDbId, userEffectiveTags))
                    .toList();
            attachFileNamesAndImageUrls(results);
            return results;
        } catch (Exception e) {
            logger.error("多模态纯文本检索失败", e);
            return new ArrayList<>();
        }
    }

    private List<Float> embedToVectorList(MultiEmbeddingClient.EmbeddingInput input, String requesterId) {
        try {
            List<float[]> vecs = multiEmbeddingClient.embedWithUsage(
                    List.of(input),
                    requesterId,
                    MultiEmbeddingClient.UsageType.QUERY
            ).vectors();
            if (vecs == null || vecs.isEmpty()) {
                return null;
            }
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }
            return list;
        } catch (Exception e) {
            logger.error("生成多模态查询向量失败", e);
            return null;
        }
    }

    private boolean currentPermission(SearchResult result,String requester,List<String> orgs) {
        if(result.getFileMd5()==null || result.getUserId()==null) return false;
        if(knowledgeAccess!=null) {
            var identity=knowledgeAccess.resolve(requester);
            return fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(result.getFileMd5(),result.getUserId()).filter(file->knowledgeAccess.readable(file,identity)).isPresent();
        }
        return fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(result.getFileMd5(),result.getUserId())
            .filter(file->file.isPublic() || requester.equals(file.getUserId()) || orgs.contains(file.getOrgTag())).isPresent();
    }

    private SearchResult toSearchResult(MultimodalEsDocument source, Double score, String retrievalMode) {
        SearchResult result = new SearchResult(
                source == null ? null : source.getFileMd5(),
                source == null ? null : source.getChunkId(),
                source == null ? null : resolveDisplayText(source),
                score,
                source == null ? null : source.getUserId(),
                source == null ? null : source.getOrgTag(),
                source != null && source.isPublic(),
                source == null ? null : source.getFileName(),
                source == null ? null : source.getPageNumber(),
                source == null ? null : source.getAnchorText(),
                retrievalMode,
                source == null ? null : resolveDisplayText(source)
        );
        if (source == null) {
            return result;
        }

        result.setItemId(source.getItemId());
        result.setKnowledgeType(source.getKnowledgeType());
        result.setSearchText(source.getSearchText());
        result.setImageText(source.getImageText());
        result.setImageSourceType(source.getImageSourceType());
        result.setImageUrl(source.getImageUrl());
        result.setBucketName(source.getBucketName());
        result.setObjectName(source.getObjectName());
        result.setEmbeddedImageId(source.getEmbeddedImageId());
        result.setImageSequence(source.getImageSequence());
        result.setContentType(source.getContentType());
        result.setWidth(source.getWidth());
        result.setHeight(source.getHeight());
        return result;
    }

    private String resolveDisplayText(MultimodalEsDocument source) {
        if (source == null) {
            return "";
        }
        if (KnowledgeMultimodalItem.TYPE_DOCUMENT_IMAGE.equals(source.getKnowledgeType())
                || KnowledgeMultimodalItem.TYPE_STANDALONE_IMAGE.equals(source.getKnowledgeType())) {
            return source.getImageText() != null ? source.getImageText() : source.getSearchText();
        }
        return source.getTextContent() != null ? source.getTextContent() : source.getSearchText();
    }

    private void attachFileNamesAndImageUrls(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .filter(md5 -> md5 != null && !md5.isBlank())
                    .collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new ArrayList<>(md5Set));
            Map<String, FileUpload> md5ToUpload = uploads.stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, item -> item, (existing, replacement) -> existing));
            for (SearchResult result : results) {
                FileUpload upload = md5ToUpload.get(result.getFileMd5());
                if (upload != null && (result.getFileName() == null || result.getFileName().isBlank())) {
                    result.setFileName(upload.getFileName());
                }
                if (KnowledgeMultimodalItem.TYPE_STANDALONE_IMAGE.equals(result.getKnowledgeType())) {
                    try {
                        result.setImageUrl(uploadService.transToPublicUrl(uploadService.generateMergedObjectUrl(result.getFileMd5())));
                    } catch (Exception e) {
                        logger.warn("生成独立图片预览 URL 失败: fileMd5={}, error={}", result.getFileMd5(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("补充多模态搜索结果文件信息失败", e);
        }
    }

    private List<String> getUserEffectiveOrgTags(String userId) {
        if(knowledgeAccess!=null) return new ArrayList<>(knowledgeAccess.resolve(userId).orgTags());
        try {
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                user = userRepository.findById(userIdLong)
                        .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
            } catch (NumberFormatException e) {
                user = userRepository.findByUsername(userId)
                        .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
            }
            return orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private String getUserDbId(String userId) {
        try {
            try {
                Long userIdLong = Long.parseLong(userId);
                userRepository.findById(userIdLong)
                        .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                return userIdLong.toString();
            } catch (NumberFormatException e) {
                User user = userRepository.findByUsername(userId)
                        .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                return user.getId().toString();
            }
        } catch (Exception e) {
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }
}
