package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.yizhaoqi.smartpai.entity.MultimodalEsDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class MultimodalElasticsearchService {

    private static final Logger logger = LoggerFactory.getLogger(MultimodalElasticsearchService.class);

    private final ElasticsearchClient esClient;

    @Value("${multimodal.elasticsearch.index-name:knowledge_multimodal}")
    private String indexName;

    @Value("${elasticsearch.bulk-size:500}")
    private int bulkSize;

    public MultimodalElasticsearchService(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    public void bulkIndex(List<MultimodalEsDocument> documents) {
        try {
            if (documents == null || documents.isEmpty()) {
                logger.info("没有需要索引到多模态 Elasticsearch 的文档");
                return;
            }

            int effectiveBulkSize = Math.max(1, bulkSize);
            int indexedCount = 0;
            for (int start = 0; start < documents.size(); start += effectiveBulkSize) {
                int end = Math.min(start + effectiveBulkSize, documents.size());
                List<MultimodalEsDocument> batch = documents.subList(start, end);
                BulkResponse response = executeBulk(batch);
                if (response.errors()) {
                    logBulkErrors(response, start, end);
                    throw new RuntimeException("多模态批量索引部分失败，请检查日志");
                }
                indexedCount += batch.size();
                logger.info("多模态 Elasticsearch bulk 索引批次完成: {}-{} / {}", start, end - 1, documents.size());
            }

            logger.info("多模态批量索引成功完成，文档数量: {}", indexedCount);
        } catch (Exception e) {
            logger.error("多模态批量索引失败，文档数量: {}", documents == null ? 0 : documents.size(), e);
            throw new RuntimeException("多模态批量索引失败", e);
        }
    }

    public void deleteByFileMd5(String fileMd5) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(indexName).allowNoIndices(true).ignoreUnavailable(true)
                    .query(q -> q.term(t -> t.field("fileMd5").value(fileMd5)))
            );
            esClient.deleteByQuery(request);
        } catch (Exception e) {
            throw new RuntimeException("删除多模态文档失败", e);
        }
    }

    public long countByFileMd5(String fileMd5) {
        try {
            CountResponse response = esClient.count(c -> c
                    .index(indexName)
                    .query(q -> q.term(t -> t.field("fileMd5").value(fileMd5)))
            );
            return response.count();
        } catch (Exception e) {
            throw new RuntimeException("统计多模态文档失败", e);
        }
    }

    public void deleteByFileMd5AndUserId(String md5,String userId) {
        try { esClient.deleteByQuery(d->d.index(indexName).allowNoIndices(true).ignoreUnavailable(true).query(q->q.bool(b->b
            .filter(f->f.term(t->t.field("fileMd5").value(md5))).filter(f->f.term(t->t.field("userId").value(userId)))))); }
        catch(Exception e) { throw new IllegalStateException("删除用户多模态索引失败",e); }
    }

    public String indexName() {
        return indexName;
    }

    private BulkResponse executeBulk(List<MultimodalEsDocument> documents) throws Exception {
        List<BulkOperation> bulkOperations = documents.stream()
                .map(doc -> BulkOperation.of(op -> op.index(idx -> idx
                        .index(indexName)
                        .id(doc.getId())
                        .document(doc)
                )))
                .toList();

        BulkRequest request = BulkRequest.of(b -> b.operations(bulkOperations));
        return esClient.bulk(request);
    }

    private void logBulkErrors(BulkResponse response, int start, int end) {
        List<BulkResponseItem> failedItems = new ArrayList<>();
        for (BulkResponseItem item : response.items()) {
            if (item.error() != null) {
                failedItems.add(item);
            }
        }
        logger.error("多模态批量索引过程中发生错误: range={}-{}, failedCount={}", start, end - 1, failedItems.size());
        failedItems.stream()
                .limit(10)
                .forEach(item -> logger.error("多模态文档索引失败 - ID: {}, 错误: {}", item.id(), item.error().reason()));
    }
}
