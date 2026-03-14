package com.example.erpinvoicesass.mapper;

import com.example.erpinvoicesass.entity.InvoiceRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface InvoiceRecordMapper {
    InvoiceRecord selectByOrderId(String orderId);

    InvoiceRecord selectByNuonuoSerialNo(String nuonuoSerialNo);

    int insert(InvoiceRecord record);

    int updateForUpstreamRetry(@Param("orderId") String orderId,
                               @Param("payload") String payload,
                               @Param("oldStatus") String oldStatus,
                               @Param("newStatus") String newStatus);

    int updateStatusToProcessing(@Param("orderId") String orderId,
                               @Param("oldStatus") String oldStatus,
                               @Param("newStatus") String newStatus);

    List<InvoiceRecord> selectStuckWaitCallbackRecords(@Param("threshold") LocalDateTime threshold,
                                                       @Param("limit") int limit);

    int updateCallbackResult(@Param("nuonuoSerialNo") String nuonuoSerialNo,
                             @Param("oldStatus") String oldStatus,
                             @Param("newStatus") String newStatus,
                             @Param("resPayload") String resPayload,
                             @Param("errorMsg") String errorMsg);

    List<InvoiceRecord> selectFailedRecordsForRetry(@Param("threshold") LocalDateTime threshold,
                                                  @Param("maxRetryCount") int maxRetryCount,
                                                  @Param("limit") int limit);

    int updateForRetry(@Param("orderId") String orderId,
                      @Param("oldStatus") String oldStatus,
                      @Param("newStatus") String newStatus);

    /**
     * 阶梯式自动重试查询核心 SQL
     * 策略:
     * retry_count = 0 -> 1分钟后重试
     * retry_count = 1 -> 5分钟后重试
     * retry_count = 2 -> 30分钟后重试
     */
    @Select("SELECT * FROM invoice_record " +
            "WHERE status IN ('FAIL', 'RED_FAIL') " +
            "AND retry_count < 3 " +
            "AND ( " +
            "  (retry_count = 0 AND update_time <= DATE_SUB(NOW(), INTERVAL 1 MINUTE)) OR " +
            "  (retry_count = 1 AND update_time <= DATE_SUB(NOW(), INTERVAL 5 MINUTE)) OR " +
            "  (retry_count = 2 AND update_time <= DATE_SUB(NOW(), INTERVAL 30 MINUTE)) " +
            ") LIMIT #{limit}")
    List<InvoiceRecord> selectSmartAutoRetryRecords(@Param("limit") int limit);

    @Update("UPDATE invoice_record SET status = #{newStatus}, retry_count = retry_count + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = #{oldStatus} AND retry_count < #{maxRetry}")
    int updateForAutoRetry(@Param("id") Long id, @Param("oldStatus") String oldStatus, @Param("newStatus") String newStatus, @Param("maxRetry") int maxRetry);

}
