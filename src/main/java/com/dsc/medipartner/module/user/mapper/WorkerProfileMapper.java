package com.dsc.medipartner.module.user.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dsc.medipartner.module.user.domain.dto.WorkerQuery;
import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;
import com.dsc.medipartner.module.user.domain.row.WorkerBrowseRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WorkerProfileMapper extends BaseMapper<WorkerProfile> {

    /**
     * 陪诊师浏览列表（契约 §6.1）：只出 audit_status=PASS、sys_user.status=1、worker_profile.status=1 的人。
     * sortBy 用 choose 映射到固定列名，前端传值不参与 SQL 拼接。
     */
    @Select("""
            <script>
            SELECT p.user_id AS userId, u.nickname AS nickname, u.avatar AS avatar, p.real_name AS realName,
                   p.health_cert_url AS healthCertUrl, p.audit_status AS auditStatus, p.audit_time AS auditTime,
                   p.credit_score AS creditScore, p.city AS city, p.service_types AS serviceTypes, p.intro AS intro,
                   p.good_rate AS goodRate, p.review_count AS reviewCount, p.service_count AS serviceCount,
                   p.avg_star AS avgStar, p.status AS status, p.updated_at AS updatedAt,
                   (p.id_card_no IS NOT NULL AND p.id_card_no != '') AS idCardVerified
            FROM worker_profile p
            JOIN sys_user u ON u.id = p.user_id
            WHERE p.audit_status = 'PASS' AND u.status = 1 AND p.status = 1
            <if test="q.city != null and q.city != ''"> AND p.city = #{q.city}</if>
            <if test="q.serviceType != null and q.serviceType != ''"> AND FIND_IN_SET(#{q.serviceType}, p.service_types)</if>
            <if test="q.minGoodRate != null"> AND p.good_rate &gt;= #{q.minGoodRate}</if>
            <if test="q.minCreditScore != null"> AND p.credit_score &gt;= #{q.minCreditScore}</if>
            <if test="q.keyword != null and q.keyword != ''">
                AND (u.nickname LIKE CONCAT('%', #{q.keyword}, '%')
                     OR p.real_name LIKE CONCAT('%', #{q.keyword}, '%')
                     OR p.intro LIKE CONCAT('%', #{q.keyword}, '%'))
            </if>
            <choose>
                <when test="q.sortBy == 'serviceCount'"> ORDER BY p.service_count</when>
                <when test="q.sortBy == 'creditScore'"> ORDER BY p.credit_score</when>
                <otherwise> ORDER BY p.good_rate</otherwise>
            </choose>
            <choose>
                <when test="q.asc"> ASC</when>
                <otherwise> DESC</otherwise>
            </choose>
            , p.user_id DESC
            </script>
            """)
    IPage<WorkerBrowseRow> browse(IPage<WorkerBrowseRow> page, @Param("q") WorkerQuery query);

    /** 陪诊师详情投影（契约 §6.2）：不过滤审核状态，由服务层决定 2001 / 2003 */
    @Select("""
            SELECT p.user_id AS userId, u.nickname AS nickname, u.avatar AS avatar, p.real_name AS realName,
                   p.health_cert_url AS healthCertUrl, p.audit_status AS auditStatus, p.audit_time AS auditTime,
                   p.credit_score AS creditScore, p.city AS city, p.service_types AS serviceTypes, p.intro AS intro,
                   p.good_rate AS goodRate, p.review_count AS reviewCount, p.service_count AS serviceCount,
                   p.avg_star AS avgStar, p.status AS status, p.updated_at AS updatedAt,
                   (p.id_card_no IS NOT NULL AND p.id_card_no != '') AS idCardVerified
            FROM worker_profile p
            JOIN sys_user u ON u.id = p.user_id
            WHERE p.user_id = #{userId}
            """)
    WorkerBrowseRow browseDetail(@Param("userId") Long userId);
}
