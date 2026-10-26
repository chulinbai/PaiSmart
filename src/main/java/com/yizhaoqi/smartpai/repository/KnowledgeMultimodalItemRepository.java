package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.KnowledgeMultimodalItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface KnowledgeMultimodalItemRepository extends JpaRepository<KnowledgeMultimodalItem, Long> {

    List<KnowledgeMultimodalItem> findByFileMd5OrderByIdAsc(String fileMd5);

    Optional<KnowledgeMultimodalItem> findByItemId(String itemId);

    long countByFileMd5(String fileMd5);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM knowledge_multimodal_items WHERE file_md5 = ?1", nativeQuery = true)
    void deleteByFileMd5(String fileMd5);

    @Transactional
    @Modifying
    @Query(value="DELETE FROM knowledge_multimodal_items WHERE file_md5=?1 AND user_id=?2",nativeQuery=true)
    void deleteByFileMd5AndUserId(String fileMd5,String userId);
}
