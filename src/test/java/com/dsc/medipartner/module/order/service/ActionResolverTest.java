package com.dsc.medipartner.module.order.service;

import com.dsc.medipartner.module.order.domain.enums.OrderState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ActionResolver unit tests (contract §1.5, §5.3, §7.7).
 *
 * <p>Exhaustive over all 7 states x both roles: the client renders buttons purely from
 * {@code actions[]}, so every state needs a pinned expectation. Illegal states must yield an
 * empty list rather than throwing, because read paths must not break on dirty data.
 */
class ActionResolverTest {

    private final ActionResolver resolver = new ActionResolver();

    // ---------------- user view (§5.3) ----------------

    @Test
    void userWaitingAcceptCanCancelAndReorder() {
        assertThat(resolver.resolveForUser(OrderState.WAITING_ACCEPT.getCode(), false))
                .containsExactly(ActionResolver.CANCEL, ActionResolver.REORDER);
    }

    @Test
    void userAcceptedCanContactAndViewTrace() {
        assertThat(resolver.resolveForUser(OrderState.ACCEPTED.getCode(), false))
                .containsExactly(ActionResolver.CONTACT, ActionResolver.VIEW_TRACE);
    }

    @Test
    void userInServiceCanContactAndViewTrace() {
        assertThat(resolver.resolveForUser(OrderState.IN_SERVICE.getCode(), false))
                .containsExactly(ActionResolver.CONTACT, ActionResolver.VIEW_TRACE);
    }

    @Test
    void userPendingConfirmCanConfirmAndAppeal() {
        List<String> actions = resolver.resolveForUser(OrderState.PENDING_CONFIRM.getCode(), false);
        assertThat(actions).contains(ActionResolver.CONFIRM, ActionResolver.APPEAL,
                ActionResolver.VIEW_REPORT);
        // 待确认阶段不接受取消（附录 A：40 -> CANCELLED 不存在）
        assertThat(actions).doesNotContain(ActionResolver.CANCEL);
    }

    @Test
    void userCompletedOffersReviewWhenNotYetReviewed() {
        List<String> actions = resolver.resolveForUser(OrderState.COMPLETED.getCode(), false);
        assertThat(actions).contains(ActionResolver.REVIEW);
        assertThat(actions).doesNotContain(ActionResolver.APPEND_REVIEW);
    }

    @Test
    void userCompletedOffersAppendWhenAlreadyReviewed() {
        List<String> actions = resolver.resolveForUser(OrderState.COMPLETED.getCode(), true);
        assertThat(actions).contains(ActionResolver.APPEND_REVIEW);
        // 一单一评：已评价后不再给 REVIEW
        assertThat(actions).doesNotContain(ActionResolver.REVIEW);
    }

    /** Cancelled is terminal: only a re-order makes sense, no cancel/confirm. */
    @Test
    void userCancelledOnlyAllowsReorder() {
        assertThat(resolver.resolveForUser(OrderState.CANCELLED.getCode(), false))
                .containsExactly(ActionResolver.REORDER);
    }

    /** Abnormal orders have no self-service exit; funds stay frozen (D9, §7.15). */
    @Test
    void userAbnormalHasNoSelfServiceExit() {
        List<String> actions = resolver.resolveForUser(OrderState.ABNORMAL.getCode(), false);
        assertThat(actions).contains(ActionResolver.APPEAL);
        assertThat(actions).doesNotContain(ActionResolver.CANCEL, ActionResolver.CONFIRM);
    }

    /** Cancel is only ever offered in WAITING_ACCEPT (appendix A forbids it afterwards). */
    @Test
    void cancelIsOnlyOfferedBeforeAcceptance() {
        assertThat(resolver.resolveForUser(OrderState.WAITING_ACCEPT.getCode(), false))
                .contains(ActionResolver.CANCEL);
        for (OrderState state : List.of(OrderState.ACCEPTED, OrderState.IN_SERVICE,
                OrderState.PENDING_CONFIRM, OrderState.COMPLETED, OrderState.CANCELLED,
                OrderState.ABNORMAL)) {
            assertThat(resolver.resolveForUser(state.getCode(), false))
                    .as("state=%s", state)
                    .doesNotContain(ActionResolver.CANCEL);
        }
    }

    // ---------------- worker view (§7.7) ----------------

    @Test
    void workerAcceptedCanStart() {
        assertThat(resolver.resolveForWorker(OrderState.ACCEPTED.getCode(), false))
                .containsExactly(ActionResolver.START, ActionResolver.CONTACT,
                        ActionResolver.VIEW_TIMELINE);
    }

    @Test
    void workerInServiceCanTraceFinishAndMarkAbnormal() {
        List<String> actions = resolver.resolveForWorker(OrderState.IN_SERVICE.getCode(), false);
        assertThat(actions).contains(ActionResolver.UPLOAD_TRACE, ActionResolver.FINISH,
                ActionResolver.MARK_ABNORMAL);
    }

    @Test
    void workerPendingConfirmOnlyWatches() {
        List<String> actions = resolver.resolveForWorker(OrderState.PENDING_CONFIRM.getCode(), false);
        assertThat(actions).contains(ActionResolver.VIEW_TIMELINE);
        assertThat(actions).doesNotContain(ActionResolver.FINISH, ActionResolver.UPLOAD_TRACE);
    }

    @Test
    void workerCompletedCanReplyWhenNotYetReplied() {
        assertThat(resolver.resolveForWorker(OrderState.COMPLETED.getCode(), false))
                .contains(ActionResolver.REPLY_REVIEW);
    }

    @Test
    void workerCompletedCannotReplyTwice() {
        assertThat(resolver.resolveForWorker(OrderState.COMPLETED.getCode(), true))
                .doesNotContain(ActionResolver.REPLY_REVIEW);
    }

    /** The hall never hands out actions; the worker view is where they appear. */
    @Test
    void workerWaitingAcceptHasNoActions() {
        assertThat(resolver.resolveForWorker(OrderState.WAITING_ACCEPT.getCode(), false)).isEmpty();
    }

    @Test
    void workerCancelledAndAbnormalCanOnlyViewTimeline() {
        assertThat(resolver.resolveForWorker(OrderState.CANCELLED.getCode(), false))
                .containsExactly(ActionResolver.VIEW_TIMELINE);
        assertThat(resolver.resolveForWorker(OrderState.ABNORMAL.getCode(), false))
                .containsExactly(ActionResolver.VIEW_TIMELINE);
    }

    // ---------------- robustness ----------------

    @Test
    void nullStatusYieldsEmptyList() {
        assertThat(resolver.resolveForUser(null, false)).isEmpty();
        assertThat(resolver.resolveForWorker(null, false)).isEmpty();
    }

    @Test
    void unknownStatusYieldsEmptyListInsteadOfThrowing() {
        assertThat(resolver.resolveForUser(999, false)).isEmpty();
        assertThat(resolver.resolveForWorker(-1, false)).isEmpty();
    }

    /** Every state resolves for both roles without throwing. */
    @Test
    void everyStateResolvesWithoutError() {
        for (OrderState state : OrderState.values()) {
            assertThat(resolver.resolveForUser(state.getCode(), false)).isNotNull();
            assertThat(resolver.resolveForUser(state.getCode(), true)).isNotNull();
            assertThat(resolver.resolveForWorker(state.getCode(), false)).isNotNull();
            assertThat(resolver.resolveForWorker(state.getCode(), true)).isNotNull();
        }
    }

    /** Action codes must be the stable uppercase contract strings, never Chinese text. */
    @Test
    void actionCodesAreStableUppercaseTokens() {
        for (OrderState state : OrderState.values()) {
            for (String code : resolver.resolveForUser(state.getCode(), false)) {
                assertThat(code).matches("[A-Z_]+");
            }
            for (String code : resolver.resolveForWorker(state.getCode(), false)) {
                assertThat(code).matches("[A-Z_]+");
            }
        }
    }
}
