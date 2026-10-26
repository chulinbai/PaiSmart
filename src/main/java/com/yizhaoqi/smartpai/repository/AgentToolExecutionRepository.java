package com.yizhaoqi.smartpai.repository;
import com.yizhaoqi.smartpai.model.AgentToolExecution;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
public interface AgentToolExecutionRepository extends JpaRepository<AgentToolExecution,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AgentToolExecution t where t.id=:id")
    Optional<AgentToolExecution> lock(@Param("id") String id);
    @Modifying
    @org.springframework.transaction.annotation.Transactional(timeout=5)
    @Query(value="UPDATE agent_tool_execution SET diagnostic='OUTCOME_UNKNOWN: service restarted; automatic replay forbidden' WHERE status='EXECUTING' AND updated_at<:startup AND diagnostic IS NULL",nativeQuery=true)
    int markInterrupted(@Param("startup") java.time.Instant startup);
}
