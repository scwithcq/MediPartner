package com.dsc.medipartner.module.chat.mapper;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.chat.domain.ChatConversation;
import org.apache.ibatis.annotations.*; import java.util.List;
@Mapper public interface ChatOrderMapper {
 @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
 @Select("SELECT * FROM service_order WHERE order_no=#{orderNo} FOR UPDATE")
 ServiceOrder lockOrder(String orderNo);
 @Select("SELECT c.* FROM chat_conversation c JOIN service_order o ON o.id=c.order_id AND o.worker_id=c.worker_id AND COALESCE(o.accepted_at,o.created_at)=c.accepted_at WHERE (c.user_id=#{userId} OR c.worker_id=#{userId}) ORDER BY c.updated_at DESC LIMIT 100")
 List<ChatConversation> inbox(Long userId);
 @Select("SELECT COUNT(*) FROM appeal_ticket WHERE order_no=#{orderNo} AND status IN ('PENDING','PROCESSING')")
 int activeAppeals(String orderNo);
 @Select("SELECT COUNT(*) FROM appeal_ticket WHERE order_no=#{orderNo} AND appeal_no=#{appealNo}")
 int appealMatches(@Param("orderNo") String orderNo,@Param("appealNo") String appealNo);
 @Insert("INSERT INTO chat_access_audit (admin_id,order_no,appeal_no,reason,created_at) VALUES (#{adminId},#{orderNo},#{appealNo},#{reason},NOW())")
 void audit(@Param("adminId") Long adminId,@Param("orderNo") String orderNo,@Param("appealNo") String appealNo,@Param("reason") String reason);
 @Select("SELECT c.* FROM chat_conversation c JOIN service_order o ON o.id=c.order_id WHERE c.purged_at IS NULL AND o.order_status IN (50,60) AND COALESCE(o.confirmed_at,o.updated_at) < DATE_SUB(NOW(),INTERVAL 90 DAY) AND NOT EXISTS (SELECT 1 FROM appeal_ticket a WHERE a.order_no=o.order_no AND a.status IN ('PENDING','PROCESSING')) LIMIT 100")
 List<ChatConversation> expired();
}
