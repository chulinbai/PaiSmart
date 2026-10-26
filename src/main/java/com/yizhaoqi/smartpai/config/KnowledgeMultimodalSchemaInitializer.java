package com.yizhaoqi.smartpai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;

@Component
@Order(1)
@ConditionalOnProperty(name = "knowledge.multimodal.schema-migration.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeMultimodalSchemaInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeMultimodalSchemaInitializer.class);
    private static final String TABLE_NAME = "knowledge_multimodal_items";

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public KnowledgeMultimodalSchemaInitializer(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @Override
    public void run(String... args) {
        if (!isMysqlCompatibleDatabase()) {
            logger.debug("跳过多模态知识项 MySQL 字段迁移：当前数据库不是 MySQL/MariaDB");
            return;
        }

        ensureLongTextColumn("search_text", "统一检索文本，文本块为正文，图片为图片说明和上下文");
        ensureLongTextColumn("text_content", "文本切片内容，仅TEXT_CHUNK使用");
        ensureLongTextColumn("image_text", "图片联合embedding时使用的文字上下文");
    }

    private boolean isMysqlCompatibleDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            String normalized = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
            return normalized.contains("mysql") || normalized.contains("mariadb");
        } catch (Exception e) {
            logger.warn("无法识别数据库类型，跳过多模态字段迁移: {}", e.getMessage());
            return false;
        }
    }

    private void ensureLongTextColumn(String columnName, String comment) {
        try {
            List<String> dataTypes = jdbcTemplate.queryForList(
                    """
                    SELECT DATA_TYPE
                    FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = ?
                      AND COLUMN_NAME = ?
                    """,
                    String.class,
                    TABLE_NAME,
                    columnName
            );
            if (dataTypes.isEmpty()) {
                logger.debug("多模态知识项字段不存在，跳过迁移: {}.{}", TABLE_NAME, columnName);
                return;
            }

            String currentType = dataTypes.get(0) == null ? "" : dataTypes.get(0).toLowerCase(Locale.ROOT);
            if ("longtext".equals(currentType)) {
                return;
            }

            String escapedComment = comment.replace("'", "''");
            String sql = "ALTER TABLE `" + TABLE_NAME + "` MODIFY COLUMN `" + columnName
                    + "` LONGTEXT COMMENT '" + escapedComment + "'";
            jdbcTemplate.execute(sql);
            logger.info("已将多模态知识项字段扩展为 LONGTEXT: {}.{} (原类型: {})",
                    TABLE_NAME, columnName, currentType);
        } catch (Exception e) {
            throw new RuntimeException("迁移多模态知识项字段失败: " + TABLE_NAME + "." + columnName, e);
        }
    }
}
