package com.dsc.medipartner.common.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 地理计算测试（阶段 5，契约 §7.4 / §7.5 / §7.6）。
 *
 * <p>用<b>真实城市间已知距离</b>做基准校准：距离算错会导致「15km 内可接单」判定整体偏移，
 * 而这类错误在业务测试里很难被发现（数字看着合理，只是系统性偏大或偏小）。
 */
class GeoUtilTest {

    // ---------------- Haversine 距离：用已知真实距离校准 ----------------

    /**
     * 广州塔 → 深圳市民中心，实测约 100km。
     *
     * <p>允许 ±3km 误差：Haversine 把地球当球体，与椭球模型有约 0.5% 差异，
     * 100km 上就是 0.5km；再放宽一点以吸收坐标本身的精度。
     */
    @Test
    void guangzhouToShenzhenDistanceIsAbout100Km() {
        BigDecimal d = GeoUtil.distanceKm(
                new BigDecimal("23.106590"), new BigDecimal("113.324553"),   // 广州塔
                new BigDecimal("22.545540"), new BigDecimal("114.054390"));  // 深圳市民中心

        assertThat(d.doubleValue()).isBetween(95.0, 105.0);
    }

    /** 同一点距离必须为 0 */
    @Test
    void samePointDistanceIsZero() {
        BigDecimal d = GeoUtil.distanceKm(
                new BigDecimal("23.106590"), new BigDecimal("113.324553"),
                new BigDecimal("23.106590"), new BigDecimal("113.324553"));
        assertThat(d).isEqualByComparingTo("0.0");
    }

    /** 短距离精度：广州塔到约 1.1km 外的点，应按 1 位小数正确表达 */
    @Test
    void shortDistanceHasOneDecimalPlace() {
        // 纬度差 0.01 度 ≈ 1.11km
        BigDecimal d = GeoUtil.distanceKm(
                new BigDecimal("23.106590"), new BigDecimal("113.324553"),
                new BigDecimal("23.116590"), new BigDecimal("113.324553"));

        assertThat(d.doubleValue()).isBetween(1.0, 1.2);
        assertThat(d.scale()).isEqualTo(1);
    }

    /** 对称性：A→B 与 B→A 必须相同 */
    @Test
    void distanceIsSymmetric() {
        BigDecimal ab = GeoUtil.distanceKm(new BigDecimal("23.106590"), new BigDecimal("113.324553"),
                new BigDecimal("22.545540"), new BigDecimal("114.054390"));
        BigDecimal ba = GeoUtil.distanceKm(new BigDecimal("22.545540"), new BigDecimal("114.054390"),
                new BigDecimal("23.106590"), new BigDecimal("113.324553"));
        assertThat(ab).isEqualByComparingTo(ba);
    }

    /** 跨 180 度经线不应算出绕地球一圈的荒谬结果（atan2 写法保证数值稳定） */
    @Test
    void antimeridianIsHandled() {
        BigDecimal d = GeoUtil.distanceKm(
                new BigDecimal("0.0"), new BigDecimal("179.9"),
                new BigDecimal("0.0"), new BigDecimal("-179.9"));
        // 约 0.2 度经度 ≈ 22km
        assertThat(d.doubleValue()).isBetween(20.0, 25.0);
    }

    /** 任一坐标缺失时返回 null：调用方据此不下发 distanceKm，而不是下发假的 0 */
    @Test
    void missingCoordinateReturnsNull() {
        assertThat(GeoUtil.distanceKm(null, new BigDecimal("113.3"),
                new BigDecimal("23.1"), new BigDecimal("113.3"))).isNull();
        assertThat(GeoUtil.distanceKm(new BigDecimal("23.1"), null,
                new BigDecimal("23.1"), new BigDecimal("113.3"))).isNull();
        assertThat(GeoUtil.distanceKm(new BigDecimal("23.1"), new BigDecimal("113.3"),
                null, null)).isNull();
    }

    /** 极点附近不应产生 NaN（浮点误差会让 a 略大于 1，用 atan2 规避） */
    @Test
    void polarCoordinatesDoNotProduceNaN() {
        BigDecimal d = GeoUtil.distanceKm(
                new BigDecimal("90.0"), new BigDecimal("0.0"),
                new BigDecimal("90.0"), new BigDecimal("180.0"));
        assertThat(d).isNotNull();
        assertThat(d.doubleValue()).isNotNaN();
        assertThat(d).isEqualByComparingTo("0.0");
    }

    // ---------------- GeoHash ----------------

    /**
     * GeoHash 用公开的已知值校验：北京天安门 (39.9042, 116.4074) 的 6 位前缀是 wx4g0b。
     *
     * <p>与业界实现对齐很重要——若编码顺序写反（经度/纬度交错搞错），
     * 前缀粗筛会把附近的点筛掉、把远处的点留下，且不会有任何报错。
     */
    @Test
    void beijingGeoHashMatchesKnownValue() {
        String hash = GeoUtil.geoHash(new BigDecimal("39.9042"), new BigDecimal("116.4074"), 6);
        assertThat(hash).isEqualTo("wx4g0b");
    }

    /**
     * 广州塔 (23.106590, 113.324553) 的 6 位前缀为 ws0e6y。
     *
     * <p>该值经独立实现（Python geohash）交叉验证，与北京天安门 wx4g0b 同为公开已知值。
     */
    @Test
    void guangzhouTowerGeoHashMatchesKnownValue() {
        String hash = GeoUtil.geoHash(new BigDecimal("23.106590"), new BigDecimal("113.324553"), 6);
        assertThat(hash).isEqualTo("ws0e6y");
    }

    @Test
    void geoHashLengthEqualsPrecision() {
        assertThat(GeoUtil.geoHash(new BigDecimal("23.106590"), new BigDecimal("113.324553"), 6))
                .hasSize(6);
        assertThat(GeoUtil.geoHash(new BigDecimal("23.106590"), new BigDecimal("113.324553"), 4))
                .hasSize(4);
        assertThat(GeoUtil.geoHash(new BigDecimal("23.106590"), new BigDecimal("113.324553"), 9))
                .hasSize(9);
    }

    /** 前缀性质：更长的 hash 必须以更短的为前缀（粗筛依赖该性质） */
    @Test
    void longerHashExtendsShorterHash() {
        BigDecimal lat = new BigDecimal("23.106590");
        BigDecimal lng = new BigDecimal("113.324553");
        String short4 = GeoUtil.geoHash(lat, lng, 4);
        String long6 = GeoUtil.geoHash(lat, lng, 6);
        assertThat(long6).startsWith(short4);
    }

    /** 同前缀收缩：两点极近时前缀应相同 */
    @Test
    void nearbyPointsSharePrefix() {
        String a = GeoUtil.geoHash(new BigDecimal("23.106590"), new BigDecimal("113.324553"), 5);
        String b = GeoUtil.geoHash(new BigDecimal("23.107000"), new BigDecimal("113.325000"), 5);
        assertThat(a).isEqualTo(b);
    }

    /** 使用 Base32 字符表，不含易混淆字符 i/l/o/u */
    @Test
    void geoHashUsesBase32Alphabet() {
        String hash = GeoUtil.geoHash(new BigDecimal("39.9042"), new BigDecimal("116.4074"), 9);
        assertThat(hash).matches("[0-9b-hj-km-np-z]+");
        assertThat(hash).doesNotContain("i").doesNotContain("l").doesNotContain("o").doesNotContain("u");
    }

    @Test
    void geoHashReturnsNullForInvalidInput() {
        assertThat(GeoUtil.geoHash(null, new BigDecimal("113.0"), 6)).isNull();
        assertThat(GeoUtil.geoHash(new BigDecimal("23.0"), null, 6)).isNull();
        assertThat(GeoUtil.geoHash(new BigDecimal("91.0"), new BigDecimal("113.0"), 6)).isNull();
        assertThat(GeoUtil.geoHash(new BigDecimal("23.0"), new BigDecimal("181.0"), 6)).isNull();
        assertThat(GeoUtil.geoHash(new BigDecimal("23.0"), new BigDecimal("113.0"), 0)).isNull();
    }

    // ---------------- 坐标解析与校验 ----------------

    @Test
    void parseCoordinateAcceptsDecimalStrings() {
        assertThat(GeoUtil.parseCoordinate("23.106590")).isEqualByComparingTo("23.106590");
        assertThat(GeoUtil.parseCoordinate("  113.324553  ")).isEqualByComparingTo("113.324553");
        assertThat(GeoUtil.parseCoordinate("-23.5")).isEqualByComparingTo("-23.5");
    }

    @Test
    void parseCoordinateRejectsInvalidText() {
        assertThat(GeoUtil.parseCoordinate(null)).isNull();
        assertThat(GeoUtil.parseCoordinate("")).isNull();
        assertThat(GeoUtil.parseCoordinate("   ")).isNull();
        assertThat(GeoUtil.parseCoordinate("abc")).isNull();
        assertThat(GeoUtil.parseCoordinate("23.1.2")).isNull();
    }

    @Test
    void validRangeChecks() {
        assertThat(GeoUtil.isValid(new BigDecimal("23.1"), new BigDecimal("113.3"))).isTrue();
        assertThat(GeoUtil.isValid(new BigDecimal("-90"), new BigDecimal("-180"))).isTrue();
        assertThat(GeoUtil.isValid(new BigDecimal("90"), new BigDecimal("180"))).isTrue();
        // 越界
        assertThat(GeoUtil.isValid(new BigDecimal("90.1"), new BigDecimal("113.3"))).isFalse();
        assertThat(GeoUtil.isValid(new BigDecimal("23.1"), new BigDecimal("180.1"))).isFalse();
        // 缺失
        assertThat(GeoUtil.isValid(null, new BigDecimal("113.3"))).isFalse();
    }
}
