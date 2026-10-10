package com.dsc.medipartner.common.result;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SortWhitelist unit tests (contract §1.3, §1.8; appendix H-9).
 *
 * <p>The security-critical property: whatever the client sends, {@code resolve} must return a
 * column name taken from the hard-coded whitelist map, never the raw request string.
 */
class SortWhitelistTest {

    /** Payloads a client might send to try to reach raw SQL. */
    private static final String[] INJECTION_PAYLOADS = {
            "id; DROP TABLE service_order",
            "1=1",
            "created_at)",
            "createdAt; DELETE FROM wallet_transaction",
            "version",
            "userId",
            "amount desc, (SELECT 1)",
            "createdAt`",
            "created_at/**/",
            "ORDER BY id",
            "(CASE WHEN 1=1 THEN created_at ELSE amount END)",
            "id--",
            "id#",
            "id' OR '1'='1",
            "SLEEP(5)",
            "createdAt,version"
    };

    // ---------------- defaults per §1.8 ----------------

    @Test
    void orderListDefaultsToCreatedAtDesc() {
        SortWhitelist.Order ord = SortWhitelist.Spec.ORDER_LIST.resolve(null, null);
        assertThat(ord.property()).isEqualTo("createdAt");
        assertThat(ord.sqlColumn()).isEqualTo("created_at");
        assertThat(ord.asc()).isFalse();
    }

    @Test
    void orderHallDefaultsToServiceTimeAsc() {
        SortWhitelist.Order ord = SortWhitelist.Spec.ORDER_HALL_ASC.resolve(null, null);
        assertThat(ord.property()).isEqualTo("serviceTime");
        assertThat(ord.asc()).isTrue();
    }

    @Test
    void workerListDefaultsToGoodRateDesc() {
        SortWhitelist.Order ord = SortWhitelist.Spec.WORKER_LIST.resolve(null, null);
        assertThat(ord.property()).isEqualTo("goodRate");
        assertThat(ord.asc()).isFalse();
    }

    @Test
    void walletTxnDefaultsToCreatedAtDesc() {
        SortWhitelist.Order ord = SortWhitelist.Spec.WALLET_TXN.resolve(null, null);
        assertThat(ord.property()).isEqualTo("createdAt");
        assertThat(ord.asc()).isFalse();
    }

    // ---------------- whitelisted values are honoured ----------------

    @Test
    void whitelistedValuesAreHonoured() {
        assertThat(SortWhitelist.Spec.ORDER_LIST.resolve("amount", "asc").sqlColumn()).isEqualTo("amount");
        assertThat(SortWhitelist.Spec.ORDER_LIST.resolve("amount", "asc").asc()).isTrue();
        assertThat(SortWhitelist.Spec.ORDER_LIST.resolve("serviceTime", "desc").sqlColumn())
                .isEqualTo("service_time");
        assertThat(SortWhitelist.Spec.WORKER_LIST.resolve("serviceCount", "asc").sqlColumn())
                .isEqualTo("service_count");
        assertThat(SortWhitelist.Spec.WORKER_LIST.resolve("creditScore", "asc").sqlColumn())
                .isEqualTo("credit_score");
    }

    /** Surrounding whitespace is trimmed before matching. */
    @Test
    void trimsSurroundingWhitespace() {
        assertThat(SortWhitelist.Spec.ORDER_LIST.resolve("  amount  ", "asc").sqlColumn())
                .isEqualTo("amount");
    }

    /** Matching is exact: a value valid for another endpoint falls back for this one. */
    @Test
    void valueValidElsewhereFallsBackForThisEndpoint() {
        // goodRate is only whitelisted for WORKER_LIST, not for ORDER_LIST
        SortWhitelist.Order ord = SortWhitelist.Spec.ORDER_LIST.resolve("goodRate", null);
        assertThat(ord.property()).isEqualTo("createdAt");
    }

    @Test
    void distanceIsWhitelistedWhereContractSaysSo() {
        assertThat(SortWhitelist.Spec.WORKER_LIST.allowedProperties()).contains("distance");
        assertThat(SortWhitelist.Spec.ORDER_HALL_ASC.allowedProperties()).contains("distance");
        assertThat(SortWhitelist.Spec.ORDER_LIST.allowedProperties()).doesNotContain("distance");
    }

    // ---------------- direction handling ----------------

    @Test
    void directionIsCaseInsensitive() {
        assertThat(SortWhitelist.Spec.ORDER_LIST.resolve("amount", "ASC").asc()).isTrue();
        assertThat(SortWhitelist.Spec.ORDER_LIST.resolve("amount", "Asc").asc()).isTrue();
        assertThat(SortWhitelist.Spec.ORDER_LIST.resolve("amount", "DESC").asc()).isFalse();
    }

    /** An unrecognised direction falls back to the endpoint default rather than failing. */
    @Test
    void invalidDirectionFallsBackToEndpointDefault() {
        // ORDER_HALL default is asc
        assertThat(SortWhitelist.Spec.ORDER_HALL_ASC.resolve("amount", "sideways").asc()).isTrue();
        // ORDER_LIST default is desc
        assertThat(SortWhitelist.Spec.ORDER_LIST.resolve("amount", "sideways").asc()).isFalse();
        assertThat(SortWhitelist.Spec.ORDER_LIST.resolve("amount", "").asc()).isFalse();
    }

    // ---------------- the security core ----------------

    /**
     * H-9 core: every injection payload must resolve to a whitelisted column, never the payload.
     * Contract §1.8 also requires no exception to be thrown.
     */
    @Test
    void injectionPayloadsNeverReachSql() {
        Set<String> safeColumns = Set.of("created_at", "service_time", "amount",
                "good_rate", "service_count", "credit_score", "geo_hash");

        for (SortWhitelist.Spec spec : SortWhitelist.Spec.values()) {
            for (String payload : INJECTION_PAYLOADS) {
                SortWhitelist.Order ord = spec.resolve(payload, "asc");
                assertThat(ord.sqlColumn())
                        .as("spec=%s payload=%s", spec, payload)
                        .isIn(safeColumns);
                assertThat(ord.property())
                        .as("spec=%s payload=%s", spec, payload)
                        .isIn(spec.allowedProperties());
            }
        }
    }

    /** The returned column must never contain the payload text. */
    @Test
    void resolvedColumnNeverContainsPayloadText() {
        for (String payload : INJECTION_PAYLOADS) {
            SortWhitelist.Order ord = SortWhitelist.Spec.ORDER_LIST.resolve(payload, null);
            assertThat(ord.sqlColumn()).doesNotContain(payload);
            assertThat(ord.sqlColumn()).doesNotContain(";");
            assertThat(ord.sqlColumn()).doesNotContain("(");
            assertThat(ord.sqlColumn()).doesNotContain("'");
            assertThat(ord.sqlColumn()).doesNotContain(" ");
        }
    }

    /** sortBy is case-sensitive per §1.3, so a differently-cased value is not accepted. */
    @Test
    void sortByIsCaseSensitive() {
        assertThat(SortWhitelist.Spec.ORDER_LIST.resolve("createdat", null).property())
                .isEqualTo("createdAt");
        assertThat(SortWhitelist.Spec.ORDER_LIST.resolve("AMOUNT", null).property())
                .isEqualTo("createdAt");
    }

    /** Every column mapping is a bare snake_case identifier - no SQL fragments. */
    @Test
    void everyWhitelistedPropertyMapsToBareIdentifier() {
        for (SortWhitelist.Spec spec : SortWhitelist.Spec.values()) {
            for (String property : spec.allowedProperties()) {
                SortWhitelist.Order ord = spec.resolve(property, "asc");
                assertThat(ord.sqlColumn())
                        .as("spec=%s property=%s", spec, property)
                        .matches("[a-z_]+");
            }
        }
    }
}
