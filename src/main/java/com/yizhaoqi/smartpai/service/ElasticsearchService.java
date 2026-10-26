package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.yizhaoqi.smartpai.entity.EsDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

// Elasticsearch操作封装服务
@Service
public class ElasticsearchService {

    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchService.class);

    @Autowired
    private ElasticsearchClient esClient;

    @Value("${elasticsearch.bulk-size:500}")
    private int bulkSize;

    /**
     * 批量索引文档到Elasticsearch中
     * 通过接收一个EsDocument对象列表，将这些文档批量索引到名为"knowledge_base"的索引中
     * 使用Elasticsearch的Bulk API来执行批量索引操作，以提高索引效率
     *
     * @param documents 文档列表，每个文档都将被索引到Elasticsearch中
     */
    public void bulkIndex(List<EsDocument> documents) {
        try {
            if (documents == null || documents.isEmpty()) {
                logger.info("没有需要索引到 Elasticsearch 的文档");
                return;
            }

            int effectiveBulkSize = Math.max(1, bulkSize);
            logger.info("开始批量索引文档到Elasticsearch，文档数量: {}, bulkSize: {}", documents.size(), effectiveBulkSize);

            int indexedCount = 0;
            for (int start = 0; start < documents.size(); start += effectiveBulkSize) {
                int end = Math.min(start + effectiveBulkSize, documents.size());
                List<EsDocument> batch = documents.subList(start, end);
                BulkResponse response = executeBulk(batch);
                if (response.errors()) {
                    logBulkErrors(response, start, end);
                    throw new RuntimeException("批量索引部分失败，请检查日志");
                }
                indexedCount += batch.size();
                logger.info("Elasticsearch bulk 索引批次完成: {}-{} / {}", start, end - 1, documents.size());
            }

            logger.info("批量索引成功完成，文档数量: {}", indexedCount);
        } catch (Exception e) {
            logger.error("批量索引失败，文档数量: {}", documents == null ? 0 : documents.size(), e);
            // 如果发生异常，抛出运行时异常，表明批量索引失败
            throw new RuntimeException("批量索引失败", e);
        }
    }

    private BulkResponse executeBulk(List<EsDocument> documents) throws Exception {
        List<BulkOperation> bulkOperations = documents.stream()
                .map(doc -> BulkOperation.of(op -> op.index(idx -> idx
                        .index("knowledge_base")
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
        logger.error("批量索引过程中发生错误: range={}-{}, failedCount={}", start, end - 1, failedItems.size());
        failedItems.stream()
                .limit(10)
                .forEach(item -> logger.error("文档索引失败 - ID: {}, 错误: {}", item.id(), item.error().reason()));
    }

    /**
     * 根据file_md5删除文档
     * @param fileMd5 文件指纹
     */
    public void deleteByFileMd5(String fileMd5) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index("knowledge_base")
                    .query(q -> q.term(t -> t.field("fileMd5").value(fileMd5)))
            );
            esClient.deleteByQuery(request);
        } catch (Exception e) {
            throw new RuntimeException("删除文档失败", e);
        }
    }
    public void deleteByFileMd5AndUserId(String fileMd5,String userId) {
        try {
            esClient.deleteByQuery(d->d.index("knowledge_base").ignoreUnavailable(true).query(q->q.bool(b->b
                .filter(f->f.term(t->t.field("fileMd5").value(fileMd5)))
                .filter(f->f.term(t->t.field("userId").value(userId))))));
        } catch(Exception error) { throw new IllegalStateException("删除指定上传者的旧索引失败",error); }
    }

    public long countByFileMd5(String fileMd5) {
        try {
            CountResponse response = esClient.count(c -> c
                    .index("knowledge_base")
                    .query(q -> q.term(t -> t.field("fileMd5").value(fileMd5)))
            );
            return response.count();
        } catch (Exception e) {
            throw new RuntimeException("统计文档失败", e);
        }
    }
}
