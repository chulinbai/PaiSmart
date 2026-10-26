package com.yizhaoqi.smartpai.service.knowledge;

import com.yizhaoqi.smartpai.entity.SearchResult;
import java.util.*;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;

public final class EvidenceAssembler {
    public record Assembly(String text,List<SearchResult> sources) { public Assembly { sources=List.copyOf(sources); } }
    public Assembly assemble(List<Evidence> evidence,CitationRegistry citations,int maxCharacters) {
        StringBuilder out=new StringBuilder("以下为检索证据数据。引用使用已分配的 [编号]；图谱解释需结合其原文来源，保留冲突与方向不确定性。\n");
        Map<String,SearchResult> selected=new LinkedHashMap<>();
        for(Evidence item:evidence) {
            StringBuilder block=new StringBuilder("\n证据类型：").append(item.type()).append("；检索：").append(item.retrievalPath()).append('\n');
            block.append(limit(item.content(),4000)).append('\n');
            for(var source:item.sources()) {
                int number=citations.register(source);
                block.append('[').append(number).append("] ").append(source.fileName()).append("，页码 ").append(source.pageNumber())
                    .append("\n来源：").append(limit(source.text(),800)).append('\n');
                if(block.length()>8000) break;
            }
            if(out.length()+block.length()>maxCharacters) continue;
            out.append(block);
            for(var source:item.sources()) selected.putIfAbsent(source.sourceId(),searchResult(source,item.retrievalPath()));
        }
        return new Assembly(out.toString(),new ArrayList<>(selected.values()));
    }
    public SearchResult searchResult(SourceReference source,String mode) {
        var result=new SearchResult(source.fileMd5(),source.chunkId(),source.text(),null,source.fileName());
        result.setDocumentId(source.documentId());result.setChunkSetId(source.chunkSetId());result.setSourceId(source.sourceId());
        result.setItemId(source.sourceId());result.setPageNumber(source.pageNumber());result.setAnchorText(source.anchorText());result.setRetrievalMode(mode);
        result.setKnowledgeType(source.type());result.setSearchText(source.text());result.setImageText(source.text());result.setImageSequence(source.imageSequence());
        result.setImageSourceType(source.imageSourceType());result.setBucketName(source.bucketName());result.setObjectName(source.objectName());
        result.setEmbeddedImageId(source.imageSequence()==null?null:source.fileMd5()+":p"+source.pageNumber()+":img"+source.imageSequence());
        result.setContentType(source.contentType());result.setWidth(source.width());result.setHeight(source.height());return result;
    }
    private String limit(String text,int max) { return text.length()>max?text.substring(0,max)+"…":text; }
}
