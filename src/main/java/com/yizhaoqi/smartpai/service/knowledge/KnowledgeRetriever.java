package com.yizhaoqi.smartpai.service.knowledge;

import com.yizhaoqi.smartpai.model.FileUpload;
import java.util.*;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;

public interface KnowledgeRetriever {
    IndexType type();
    Set<String> capabilities();
    List<Evidence> retrieve(RetrievalRequest request,Access access,List<FileUpload> documents) throws Exception;
}
