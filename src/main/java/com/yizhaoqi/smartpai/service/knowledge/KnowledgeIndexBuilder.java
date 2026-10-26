package com.yizhaoqi.smartpai.service.knowledge;

import java.util.Set;
import com.yizhaoqi.smartpai.model.FileUpload;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;

public interface KnowledgeIndexBuilder {
    IndexType type();
    Set<String> capabilities();
    String configVersion();
    BuildResult build(BuildContext context,ChunkSet chunks,FileUpload file) throws Exception;
}
