package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.service.MultimodalSearchService;
import com.yizhaoqi.smartpai.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.yizhaoqi.smartpai.entity.SearchResult;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

// 提供混合检索接口
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    @Autowired
    private MultimodalSearchService multimodalSearchService;

    /**
     * 混合检索接口
     * 
     * URL: /api/v1/search/hybrid
     * Method: GET
     * Parameters:
     *   - query: 搜索查询字符串（必需）
     *   - topK: 返回结果数量（可选，默认10）
     * 
     * 示例: /api/v1/search/hybrid?query=人工智能的发展&topK=10
     * 
     * Response:
     * [
     *   {
     *     "fileMd5": "abc123...",
     *     "chunkId": 1,
     *     "textContent": "人工智能是未来科技发展的核心方向。",
     *     "score": 0.92,
     *     "userId": "user123",
     *     "orgTag": "TECH_DEPT",
     *     "isPublic": true
     *   }
     * ]
     */
    @GetMapping("/hybrid")
    public Map<String, Object> hybridSearch(@RequestParam String query,
                                            @RequestParam(defaultValue = "10") int topK,
                                            @RequestAttribute(value = "userId", required = false) String userId) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("HYBRID_SEARCH");
        try {
            LogUtils.logBusiness("HYBRID_SEARCH", userId != null ? userId : "anonymous", 
                    "开始混合检索: query=%s, topK=%d", query, topK);
            
            List<SearchResult> results = userId != null
                    ? multimodalSearchService.searchWithPermission(query, userId, topK)
                    : Collections.emptyList();
            
            LogUtils.logUserOperation(userId != null ? userId : "anonymous", "HYBRID_SEARCH", 
                    "search_query", "SUCCESS");
            LogUtils.logBusiness("HYBRID_SEARCH", userId != null ? userId : "anonymous", 
                    "混合检索完成: 返回结果数量=%d", results.size());
            monitor.end("混合检索成功");
            
            // 构造统一响应结构
            Map<String, Object> responseBody = new HashMap<>(4);
            responseBody.put("code", 200);
            responseBody.put("message", "success");
            responseBody.put("data", results);
            
            return responseBody;
        } catch (Exception e) {
            LogUtils.logBusinessError("HYBRID_SEARCH", userId != null ? userId : "anonymous", 
                    "混合检索失败: query=%s", e, query);
            monitor.end("混合检索失败: " + e.getMessage());
            
            // 构造错误响应结构，保持与前端解析一致
            Map<String, Object> errorBody = new HashMap<>(4);
            errorBody.put("code", 500);
            errorBody.put("message", e.getMessage());
            errorBody.put("data", Collections.emptyList());
            return errorBody;
        }
    }

    @GetMapping("/multimodal")
    public Map<String, Object> multimodalSearch(@RequestParam String query,
                                                @RequestParam(defaultValue = "10") int topK,
                                                @RequestAttribute(value = "userId", required = false) String userId) {
        return doMultimodalSearch(query, null, topK, userId);
    }

    @PostMapping("/multimodal")
    public Map<String, Object> multimodalSearch(@RequestBody MultimodalSearchRequest request,
                                                @RequestAttribute(value = "userId", required = false) String userId) {
        String query = firstNonBlank(request == null ? null : request.query(), request == null ? null : request.text());
        String image = request == null ? null : request.image();
        int topK = request == null || request.topK() == null ? 10 : request.topK();
        return doMultimodalSearch(query, image, topK, userId);
    }

    private Map<String, Object> doMultimodalSearch(String query, String image, int topK, String userId) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("MULTIMODAL_SEARCH");
        try {
            int effectiveTopK = Math.max(1, Math.min(topK, 50));
            LogUtils.logBusiness("MULTIMODAL_SEARCH", userId != null ? userId : "anonymous",
                    "开始多模态检索: query=%s, hasImage=%s, topK=%d", query, image != null && !image.isBlank(), effectiveTopK);

            if ((query == null || query.isBlank()) && (image == null || image.isBlank())) {
                Map<String, Object> errorBody = new HashMap<>(4);
                errorBody.put("code", 400);
                errorBody.put("message", "query/text 与 image 至少提供一个");
                errorBody.put("data", Collections.emptyList());
                return errorBody;
            }

            List<SearchResult> results = userId != null
                    ? multimodalSearchService.searchWithPermission(query, image, userId, effectiveTopK)
                    : Collections.emptyList();

            monitor.end("多模态检索成功");
            Map<String, Object> responseBody = new HashMap<>(4);
            responseBody.put("code", 200);
            responseBody.put("message", "success");
            responseBody.put("data", results);
            return responseBody;
        } catch (Exception e) {
            LogUtils.logBusinessError("MULTIMODAL_SEARCH", userId != null ? userId : "anonymous",
                    "多模态检索失败: query=%s", e, query);
            monitor.end("多模态检索失败: " + e.getMessage());
            Map<String, Object> errorBody = new HashMap<>(4);
            errorBody.put("code", 500);
            errorBody.put("message", e.getMessage());
            errorBody.put("data", Collections.emptyList());
            return errorBody;
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    public record MultimodalSearchRequest(String query, String text, String image, Integer topK) {
    }
}
