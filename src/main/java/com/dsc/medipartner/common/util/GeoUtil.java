package com.dsc.medipartner.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 地理坐标与距离计算（契约 §7.4 / §7.5 / §7.6）。
 *
 * <p><b>为什么自己实现而不用现成库</b>：本项目只需要「两点球面距离」与「GeoHash 前缀」两件事，
 * 引入 GeoAPI/JTS 之类的依赖会增加构建体积与版本风险，而 Haversine 公式本身只有十几行，
 * 且能被单元测试逐位验证。
 *
 * <p><b>距离口径</b>：Haversine 把地球当标准球体（半径 6371.0088km，IUGG 平均半径）。
 * 最大误差约 0.5%，对「15km 内可接单」这类业务判定绰绰有余
 * （若换成椭球模型 Vincenty，精度提升到毫米级但计算量翻十倍，对本场景没有意义）。
 */
public final class GeoUtil {

    /** 地球平均半径（km），IUGG 推荐值 */
    private static final double EARTH_RADIUS_KM = 6371.0088;

    /** 距离按契约要求保留 1 位小数 */
    private static final int DISTANCE_SCALE = 1;

    /** GeoHash 使用的 Base32 字符表（RFC 4648 顺序，与业界实现一致） */
    private static final char[] BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray();

    /** 合法的经纬度范围 */
    private static final double MIN_LAT = -90.0;
    private static final double MAX_LAT = 90.0;
    private static final double MIN_LNG = -180.0;
    private static final double MAX_LNG = 180.0;

    private GeoUtil() {
    }

    /**
     * 两点球面距离（km，保留 1 位小数）。
     *
     * @return 距离；任一坐标为 null 时返回 {@code null}（表示「无法计算」，
     *         调用方据此决定不下发 distanceKm，而不是下发一个假的 0）
     */
    public static BigDecimal distanceKm(BigDecimal lat1, BigDecimal lng1,
                                        BigDecimal lat2, BigDecimal lng2) {
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) {
            return null;
        }
        double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLng = Math.toRadians(lng2.doubleValue() - lng1.doubleValue());
        double radLat1 = Math.toRadians(lat1.doubleValue());
        double radLat2 = Math.toRadians(lat2.doubleValue());

        // Haversine：a = sin²(Δφ/2) + cosφ1·cosφ2·sin²(Δλ/2)，d = 2R·asin(√a)
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(radLat1) * Math.cos(radLat2)
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        // 用 atan2 而非 asin：数值更稳定，且 a 因浮点误差略大于 1 时不会产生 NaN
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double km = EARTH_RADIUS_KM * c;
        return BigDecimal.valueOf(km).setScale(DISTANCE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算 GeoHash 前缀（契约 §7.4 备注 1：取前 6 位，约 1.2km 精度）。
     *
     * <p><b>用途仅限粗筛</b>：先按前缀过滤掉明显很远的候选，再在内存里用
     * {@link #distanceKm} 精算。绝不能拿 GeoHash 排序当距离排序——那是字典序，
     * 结果看似有序、实际与距离无关（这正是阶段 1.5 遗留的隐患，已修）。
     *
     * @param precision 前缀长度，契约取 6
     * @return GeoHash 字符串（小写）；坐标非法时返回 {@code null}
     */
    public static String geoHash(BigDecimal lat, BigDecimal lng, int precision) {
        if (lat == null || lng == null || precision <= 0) {
            return null;
        }
        double latitude = lat.doubleValue();
        double longitude = lng.doubleValue();
        if (!isValidLatitude(latitude) || !isValidLongitude(longitude)) {
            return null;
        }

        double[] latRange = {MIN_LAT, MAX_LAT};
        double[] lngRange = {MIN_LNG, MAX_LNG};
        StringBuilder hash = new StringBuilder(precision);
        boolean evenBit = true;   // GeoHash 交错编码：偶数位放经度，奇数位放纬度
        int bit = 0;
        int charIndex = 0;

        while (hash.length() < precision) {
            if (evenBit) {
                double mid = (lngRange[0] + lngRange[1]) / 2;
                if (longitude >= mid) {
                    charIndex = (charIndex << 1) | 1;
                    lngRange[0] = mid;
                } else {
                    charIndex = charIndex << 1;
                    lngRange[1] = mid;
                }
            } else {
                double mid = (latRange[0] + latRange[1]) / 2;
                if (latitude >= mid) {
                    charIndex = (charIndex << 1) | 1;
                    latRange[0] = mid;
                } else {
                    charIndex = charIndex << 1;
                    latRange[1] = mid;
                }
            }
            evenBit = !evenBit;
            // 每 5 bit 出一个字符
            if (++bit == 5) {
                hash.append(BASE32[charIndex]);
                bit = 0;
                charIndex = 0;
            }
        }
        return hash.toString();
    }

    /**
     * 解码 GeoHash 为格子中心点坐标。
     *
     * <p><b>为什么需要它</b>：本项目 {@code service_order} 只存了 GeoHash、没有原始经纬度列
     * （见 V1 表结构）。但 GeoHash 字符串本身编码了它所在格子的中心坐标——
     * 解码即得近似点位，误差上界为格子尺寸（6 位约 ±0.6km）。
     * 对「按距离就近排序」而言这个精度足够：排序只需要<b>相对</b>远近正确，
     * 而相邻格子的误差是同一量级的系统偏差，不会打乱大体次序。
     *
     * @param geoHash GeoHash 字符串（大小写均可）
     * @return {@code [纬度, 经度]}；输入为空或含非法字符时返回 {@code null}
     */
    public static double[] decodeGeoHashCenter(String geoHash) {
        if (geoHash == null || geoHash.isBlank()) {
            return null;
        }
        String hash = geoHash.trim().toLowerCase();
        double[] latRange = {MIN_LAT, MAX_LAT};
        double[] lngRange = {MIN_LNG, MAX_LNG};
        boolean evenBit = true;

        for (char c : hash.toCharArray()) {
            int charIndex = indexOf(c);
            if (charIndex < 0) {
                return null;
            }
            // 每个字符含 5 bit，从高位到低位依次还原
            for (int mask = 16; mask > 0; mask >>= 1) {
                boolean bitSet = (charIndex & mask) != 0;
                if (evenBit) {
                    double mid = (lngRange[0] + lngRange[1]) / 2;
                    if (bitSet) {
                        lngRange[0] = mid;
                    } else {
                        lngRange[1] = mid;
                    }
                } else {
                    double mid = (latRange[0] + latRange[1]) / 2;
                    if (bitSet) {
                        latRange[0] = mid;
                    } else {
                        latRange[1] = mid;
                    }
                }
                evenBit = !evenBit;
            }
        }
        return new double[]{(latRange[0] + latRange[1]) / 2, (lngRange[0] + lngRange[1]) / 2};
    }

    /** Base32 字符 -> 索引；非法字符返回 -1 */
    private static int indexOf(char c) {
        for (int i = 0; i < BASE32.length; i++) {
            if (BASE32[i] == c) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 解析坐标字符串为 {@link BigDecimal}。
     *
     * <p>契约把经纬度定义为「字符串保留 6 位小数」，因此请求体里是文本。
     * 用 BigDecimal 而非 double 承接，避免边界值上出现「同一坐标两次上报，
     * 一次通过校验一次不通过」的不可复现问题。
     *
     * @return 解析结果；空白或非法格式返回 {@code null}
     */
    public static BigDecimal parseCoordinate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean isValidLatitude(double latitude) {
        return latitude >= MIN_LAT && latitude <= MAX_LAT;
    }

    public static boolean isValidLongitude(double longitude) {
        return longitude >= MIN_LNG && longitude <= MAX_LNG;
    }

    /**
     * 是否为合法坐标对。
     *
     * <p>注意 (0, 0) 在数学上合法但实际位于几内亚湾——本项目不特殊处理，
     * 因为前端定位失败时契约要求<b>不调用上报接口</b>（§7.4 备注 3），
     * 后端不该猜测「这个 0 是不是没定位到」。
     */
    public static boolean isValid(BigDecimal lat, BigDecimal lng) {
        return lat != null && lng != null
                && isValidLatitude(lat.doubleValue())
                && isValidLongitude(lng.doubleValue());
    }
}
