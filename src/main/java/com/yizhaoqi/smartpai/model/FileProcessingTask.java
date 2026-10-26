package com.yizhaoqi.smartpai.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件处理任务类，用于Kafka消息传递
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FileProcessingTask {
    public static final String TASK_TYPE_UPLOAD_PROCESS = "UPLOAD_PROCESS";
    public static final String TASK_TYPE_REINDEX = "REINDEX";

    private String fileMd5; // 文件的 MD5 校验值
    private String filePath; // 文件存储路径
    private String fileName; // 文件名
    private String userId;   // 上传用户ID
    private String orgTag;   // 文件所属组织标签
    private boolean isPublic; // 文件是否公开
    private String taskType; // 任务类型
    private String requesterId; // 发起重试的用户
    private Long documentId; // Logical upload identity; MD5 is only a physical content key.

    public FileProcessingTask(String fileMd5,String filePath,String fileName,String userId,String orgTag,
            boolean isPublic,String taskType,String requesterId) {
        this(fileMd5,filePath,fileName,userId,orgTag,isPublic,taskType,requesterId,null);
    }
    
    /**
     * 向后兼容的构造函数
     */
    public FileProcessingTask(String fileMd5, String filePath, String fileName) {
        this.fileMd5 = fileMd5;
        this.filePath = filePath;
        this.fileName = fileName;
        this.userId = null;
        this.orgTag = "DEFAULT";
        this.isPublic = false;
        this.taskType = TASK_TYPE_UPLOAD_PROCESS;
        this.requesterId = null;
    }
}
