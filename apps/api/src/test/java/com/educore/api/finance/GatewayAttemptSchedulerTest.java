package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Scheduling only: no provider, database, application activation or school data. */
class GatewayAttemptSchedulerTest {
    private final GatewayProviderRegistry registry = mock(GatewayProviderRegistry.class);
    private final GatewayAttemptWorker worker = mock(GatewayAttemptWorker.class);
    private final List<Runnable> accepted = new ArrayList<>();
    private final GatewayAttemptScheduler scheduler = new GatewayAttemptScheduler(registry, worker, accepted::add);

    @Test void refundLaneSharesTheExistingTwoSlotsAndRecoversEvenWhenNewRefundsAreOff() {
        var webhook=mock(GatewayWebhookWorker.class);var refund=mock(GatewayRefundWorker.class);var route=route("a");
        when(registry.workerEnabled()).thenReturn(true);when(registry.configuredRoutes()).thenReturn(List.of(route));
        var order=new ArrayList<String>();
        when(worker.runNext(route.routeKey())).thenAnswer(call->{order.add("attempt");return GatewayAttemptWorker.Step.IDLE;});
        when(webhook.runNext(route.routeKey())).thenAnswer(call->{order.add("inbox");return GatewayAttemptWorker.Step.IDLE;});
        when(refund.runNext(route.routeKey())).thenAnswer(call->{order.add("refund");return GatewayAttemptWorker.Step.IDLE;});
        var all=new GatewayAttemptScheduler(registry,worker,webhook,refund,accepted::add);
        all.dispatchDueAttempts();assertThat(accepted).hasSize(2);accepted.forEach(Runnable::run);accepted.clear();
        all.dispatchDueAttempts();assertThat(accepted).hasSize(2);accepted.forEach(Runnable::run);
        assertThat(order).containsExactly("attempt","inbox","refund","attempt");
        verify(registry,never()).refundsEnabled(); // GET recovery cannot be starved by NEW-refund disablement.
    }

    @Test void threeLanesVisitEachSchoolWithoutAddingASeparatePoolOrUnboundedBatch() {
        var webhook=mock(GatewayWebhookWorker.class);var refund=mock(GatewayRefundWorker.class);var first=route("a");var second=route("b");
        when(registry.workerEnabled()).thenReturn(true);when(registry.configuredRoutes()).thenReturn(List.of(first,second));
        var order=new ArrayList<String>();
        when(worker.runNext(anyString())).thenAnswer(call->{order.add("attempt:"+call.getArgument(0));return GatewayAttemptWorker.Step.IDLE;});
        when(webhook.runNext(anyString())).thenAnswer(call->{order.add("inbox:"+call.getArgument(0));return GatewayAttemptWorker.Step.IDLE;});
        when(refund.runNext(anyString())).thenAnswer(call->{order.add("refund:"+call.getArgument(0));return GatewayAttemptWorker.Step.IDLE;});
        var all=new GatewayAttemptScheduler(registry,worker,webhook,refund,accepted::add);
        all.dispatchDueAttempts();all.dispatchDueAttempts();all.dispatchDueAttempts();assertThat(accepted).hasSize(6);accepted.forEach(Runnable::run);
        assertThat(order).containsExactly("attempt:"+first.routeKey(),"inbox:"+first.routeKey(),"refund:"+first.routeKey(),"attempt:"+second.routeKey(),"inbox:"+second.routeKey(),"refund:"+second.routeKey());
    }

    @Test void disabledAndUnconfiguredWorkerSchedulesNothing() {
        scheduler.dispatchDueAttempts();
        verifyNoInteractions(worker); verify(registry, never()).configuredRoutes();
        when(registry.workerEnabled()).thenReturn(true); when(registry.configuredRoutes()).thenReturn(List.of());
        scheduler.dispatchDueAttempts(); assertThat(accepted).isEmpty(); verifyNoInteractions(worker);
    }

    @Test void twoConcurrentSlotsCanServeOneSchoolWithoutCreatingAnUnboundedBatch() {
        var route = route("a");
        when(registry.workerEnabled()).thenReturn(true); when(registry.configuredRoutes()).thenReturn(List.of(route));
        when(worker.runNext(route.routeKey())).thenReturn(GatewayAttemptWorker.Step.RETRY);
        scheduler.dispatchDueAttempts(); assertThat(accepted).hasSize(2); verifyNoInteractions(worker);
        accepted.forEach(Runnable::run); verify(worker, times(16)).runNext(route.routeKey());
    }

    @Test void routeCursorContinuesFairlyAndIdleOrRecoveredStopsEachBatch() {
        var first = route("a"); var second = route("b"); var third = route("c");
        when(registry.workerEnabled()).thenReturn(true); when(registry.configuredRoutes()).thenReturn(List.of(first, second, third));
        when(worker.runNext(first.routeKey())).thenReturn(GatewayAttemptWorker.Step.IDLE);
        when(worker.runNext(second.routeKey())).thenReturn(GatewayAttemptWorker.Step.RECOVERED);
        when(worker.runNext(third.routeKey())).thenReturn(GatewayAttemptWorker.Step.COMPLETE, GatewayAttemptWorker.Step.IDLE);
        scheduler.dispatchDueAttempts(); scheduler.dispatchDueAttempts(); assertThat(accepted).hasSize(4);
        accepted.forEach(Runnable::run);
        verify(worker, times(2)).runNext(first.routeKey()); verify(worker).runNext(second.routeKey()); verify(worker, times(2)).runNext(third.routeKey());
    }

    @Test void saturatedExecutorDoesNotQueueRetryOrRunWorkOnThePollingThread() {
        when(registry.workerEnabled()).thenReturn(true); when(registry.configuredRoutes()).thenReturn(List.of(route("a")));
        Executor busy = task -> { throw new RejectedExecutionException("Synthetic busy worker"); };
        var noQueue = new GatewayAttemptScheduler(registry, worker, busy);
        assertThatCode(noQueue::dispatchDueAttempts).doesNotThrowAnyException(); verifyNoInteractions(worker);
    }

    @Test void oneAvailableSlotPerPollCannotStarveTheSecondRouteByAdvancingRejectedWork() {
        var first = route("a"); var second = route("b");
        when(registry.workerEnabled()).thenReturn(true); when(registry.configuredRoutes()).thenReturn(List.of(first, second));
        when(worker.runNext(anyString())).thenReturn(GatewayAttemptWorker.Step.IDLE);
        var remainingSlots = new AtomicInteger();
        Executor oneSlot = task -> {
            if (remainingSlots.getAndDecrement() <= 0) throw new RejectedExecutionException("Synthetic single available slot");
            accepted.add(task);
        };
        var bounded = new GatewayAttemptScheduler(registry, worker, oneSlot);
        for (int poll = 0; poll < 3; poll++) {
            remainingSlots.set(1);
            bounded.dispatchDueAttempts();
        }
        assertThat(accepted).hasSize(3); verifyNoInteractions(worker);
        accepted.forEach(Runnable::run);
        var routeKeys = ArgumentCaptor.forClass(String.class);
        verify(worker, times(3)).runNext(routeKeys.capture());
        assertThat(routeKeys.getAllValues()).containsExactly(first.routeKey(), second.routeKey(), first.routeKey());
    }

    @Test void aFullyRejectedPollPreservesItsFirstCandidateUntilTheExecutorAcceptsWork() {
        var first = route("a"); var second = route("b"); var third = route("c");
        when(registry.workerEnabled()).thenReturn(true); when(registry.configuredRoutes()).thenReturn(List.of(first, second, third));
        when(worker.runNext(anyString())).thenReturn(GatewayAttemptWorker.Step.IDLE);
        var remainingSlots = new AtomicInteger();
        Executor availableLater = task -> {
            if (remainingSlots.getAndDecrement() <= 0) throw new RejectedExecutionException("Synthetic initially busy executor");
            accepted.add(task);
        };
        var bounded = new GatewayAttemptScheduler(registry, worker, availableLater);
        bounded.dispatchDueAttempts();
        assertThat(accepted).isEmpty(); verifyNoInteractions(worker);
        remainingSlots.set(2);
        bounded.dispatchDueAttempts();
        assertThat(accepted).hasSize(2); verifyNoInteractions(worker);
        accepted.forEach(Runnable::run);
        var routeKeys = ArgumentCaptor.forClass(String.class);
        verify(worker, times(2)).runNext(routeKeys.capture());
        assertThat(routeKeys.getAllValues()).containsExactly(first.routeKey(), second.routeKey());
    }

    @Test void disablingBeforeExecutionAndCallerInterruptionLeavePendingWorkUnstarted() {
        when(registry.workerEnabled()).thenReturn(true); when(registry.configuredRoutes()).thenReturn(List.of(route("a")));
        scheduler.dispatchDueAttempts(); when(registry.workerEnabled()).thenReturn(false);
        accepted.forEach(Runnable::run); verifyNoInteractions(worker);
        accepted.clear(); when(registry.workerEnabled()).thenReturn(true);
        Thread.currentThread().interrupt();
        try { scheduler.dispatchDueAttempts(); assertThat(accepted).isEmpty(); assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
        finally { Thread.interrupted(); }
    }

    @Test void shutdownCancelsOnlyItsExecutorAndUnexpectedFailureCannotSpin() {
        var executor = mock(java.util.concurrent.ExecutorService.class);
        new GatewayAttemptScheduler(registry, worker, executor).shutdown(); verify(executor).shutdownNow();
        when(registry.workerEnabled()).thenReturn(true); var route = route("a"); when(registry.configuredRoutes()).thenReturn(List.of(route));
        when(worker.runNext(route.routeKey())).thenThrow(new IllegalStateException("Synthetic unavailable"));
        scheduler.dispatchDueAttempts(); assertThatCode(() -> accepted.forEach(Runnable::run)).doesNotThrowAnyException();
        verify(worker, times(2)).runNext(route.routeKey());
    }

    @Test void oneCampusServicesAttemptAndCallbackLanesThroughTheSameTwoBoundedSlots() {
        var webhook = mock(GatewayWebhookWorker.class); var route = route("a");
        when(registry.workerEnabled()).thenReturn(true); when(registry.configuredRoutes()).thenReturn(List.of(route));
        when(worker.runNext(route.routeKey())).thenReturn(GatewayAttemptWorker.Step.IDLE);
        when(webhook.runNext(route.routeKey())).thenReturn(GatewayAttemptWorker.Step.RETRY);
        var combined = new GatewayAttemptScheduler(registry, worker, webhook, accepted::add);
        combined.dispatchDueAttempts(); assertThat(accepted).hasSize(2); verifyNoInteractions(worker, webhook);
        accepted.forEach(Runnable::run);
        verify(worker).runNext(route.routeKey()); verify(webhook, times(8)).runNext(route.routeKey());
    }

    @Test void combinedRoundRobinVisitsEachSchoolAndItsCallbackLaneWithoutASecondExecutor() {
        var webhook = mock(GatewayWebhookWorker.class); var first = route("a"); var second = route("b");
        when(registry.workerEnabled()).thenReturn(true); when(registry.configuredRoutes()).thenReturn(List.of(first, second));
        when(worker.runNext(anyString())).thenReturn(GatewayAttemptWorker.Step.IDLE);
        when(webhook.runNext(anyString())).thenReturn(GatewayAttemptWorker.Step.RECOVERED);
        var order = new ArrayList<String>();
        doAnswer(call -> { order.add("attempt:" + call.getArgument(0, String.class)); return GatewayAttemptWorker.Step.IDLE; }).when(worker).runNext(anyString());
        doAnswer(call -> { order.add("inbox:" + call.getArgument(0, String.class)); return GatewayAttemptWorker.Step.RECOVERED; }).when(webhook).runNext(anyString());
        var combined = new GatewayAttemptScheduler(registry, worker, webhook, accepted::add);
        combined.dispatchDueAttempts(); combined.dispatchDueAttempts(); combined.dispatchDueAttempts();
        assertThat(accepted).hasSize(6); accepted.forEach(Runnable::run);
        assertThat(order).containsExactly("attempt:" + first.routeKey(), "inbox:" + first.routeKey(),
                "attempt:" + second.routeKey(), "inbox:" + second.routeKey(), "attempt:" + first.routeKey(), "inbox:" + first.routeKey());
    }

    @Test void oneAcceptedSlotPerPollDoesNotStarveCallbackLaneOrNextSchool() {
        var webhook = mock(GatewayWebhookWorker.class); var first = route("a"); var second = route("b");
        when(registry.workerEnabled()).thenReturn(true); when(registry.configuredRoutes()).thenReturn(List.of(first, second));
        var order = new ArrayList<String>();
        doAnswer(call -> { order.add("attempt:" + call.getArgument(0, String.class)); return GatewayAttemptWorker.Step.IDLE; }).when(worker).runNext(anyString());
        doAnswer(call -> { order.add("inbox:" + call.getArgument(0, String.class)); return GatewayAttemptWorker.Step.IDLE; }).when(webhook).runNext(anyString());
        var slots = new AtomicInteger(); Executor oneSlot = task -> {
            if (slots.getAndDecrement() <= 0) throw new RejectedExecutionException("Synthetic busy slot"); accepted.add(task);
        };
        var combined = new GatewayAttemptScheduler(registry, worker, webhook, oneSlot);
        for (int poll = 0; poll < 5; poll++) { slots.set(1); combined.dispatchDueAttempts(); }
        accepted.forEach(Runnable::run);
        assertThat(order).containsExactly("attempt:" + first.routeKey(), "inbox:" + first.routeKey(),
                "attempt:" + second.routeKey(), "inbox:" + second.routeKey(), "attempt:" + first.routeKey());
    }

    @Test void rejectedCombinedPollKeepsItsCandidateAndDoesNotRunEitherWorkerOnPollingThread() {
        var webhook = mock(GatewayWebhookWorker.class); var route = route("a");
        when(registry.workerEnabled()).thenReturn(true); when(registry.configuredRoutes()).thenReturn(List.of(route));
        var slots = new AtomicInteger(); Executor availableLater = task -> {
            if (slots.getAndDecrement() <= 0) throw new RejectedExecutionException("Synthetic busy slots"); accepted.add(task);
        };
        var combined = new GatewayAttemptScheduler(registry, worker, webhook, availableLater);
        combined.dispatchDueAttempts(); assertThat(accepted).isEmpty(); verifyNoInteractions(worker, webhook);
        when(worker.runNext(route.routeKey())).thenReturn(GatewayAttemptWorker.Step.IDLE);
        when(webhook.runNext(route.routeKey())).thenReturn(GatewayAttemptWorker.Step.IDLE);
        slots.set(2); combined.dispatchDueAttempts(); assertThat(accepted).hasSize(2); verifyNoInteractions(worker, webhook);
        accepted.forEach(Runnable::run); verify(worker).runNext(route.routeKey()); verify(webhook).runNext(route.routeKey());
    }

    @Test void callbackCompletionAndReviewContinueOnlyWithinEightStepBatch() {
        var webhook = mock(GatewayWebhookWorker.class); var route = route("a");
        when(registry.workerEnabled()).thenReturn(true); when(registry.configuredRoutes()).thenReturn(List.of(route));
        when(worker.runNext(route.routeKey())).thenReturn(GatewayAttemptWorker.Step.IDLE);
        when(webhook.runNext(route.routeKey())).thenReturn(GatewayAttemptWorker.Step.COMPLETE, GatewayAttemptWorker.Step.REVIEW_REQUIRED,
                GatewayAttemptWorker.Step.RETRY, GatewayAttemptWorker.Step.COMPLETE);
        new GatewayAttemptScheduler(registry, worker, webhook, accepted::add).dispatchDueAttempts();
        accepted.forEach(Runnable::run); verify(worker).runNext(route.routeKey()); verify(webhook, times(8)).runNext(route.routeKey());
    }

    @Test void callbackUnavailableAndFailureStopTheirBatchesWithoutAffectingAttemptLane() {
        var webhook = mock(GatewayWebhookWorker.class); var route = route("a");
        when(registry.workerEnabled()).thenReturn(true); when(registry.configuredRoutes()).thenReturn(List.of(route));
        when(worker.runNext(route.routeKey())).thenReturn(GatewayAttemptWorker.Step.IDLE);
        when(webhook.runNext(route.routeKey())).thenReturn(GatewayAttemptWorker.Step.UNAVAILABLE)
                .thenThrow(new IllegalStateException("Synthetic private callback reference"));
        var combined = new GatewayAttemptScheduler(registry, worker, webhook, accepted::add);
        combined.dispatchDueAttempts(); combined.dispatchDueAttempts();
        assertThatCode(() -> accepted.forEach(Runnable::run)).doesNotThrowAnyException();
        verify(worker, times(2)).runNext(route.routeKey()); verify(webhook, times(2)).runNext(route.routeKey());
    }

    @Test void combinedDisabledBeforeExecutionLeavesBothKindsUnstartedAndShutdownOwnsOnePool() {
        var webhook = mock(GatewayWebhookWorker.class); var route = route("a");
        when(registry.workerEnabled()).thenReturn(true); when(registry.configuredRoutes()).thenReturn(List.of(route));
        var combined = new GatewayAttemptScheduler(registry, worker, webhook, accepted::add);
        combined.dispatchDueAttempts(); when(registry.workerEnabled()).thenReturn(false); accepted.forEach(Runnable::run);
        verifyNoInteractions(worker, webhook);
        var pool = mock(java.util.concurrent.ExecutorService.class);
        new GatewayAttemptScheduler(registry, worker, webhook, pool).shutdown(); verify(pool).shutdownNow();
    }

    @Test void fourAdjacentLanesVisitEachSchoolThroughTheSameTwoSlots(){
        var webhook=mock(GatewayWebhookWorker.class);var refund=mock(GatewayRefundWorker.class);var settlement=mock(GatewaySettlementWorker.class);
        var first=route("a");var second=route("b");when(registry.workerEnabled()).thenReturn(true);
        when(registry.configuredRoutes()).thenReturn(List.of(first,second));when(registry.settlementsAllowed(any())).thenReturn(true);
        var order=new ArrayList<String>();
        when(worker.runNext(anyString())).thenAnswer(call->{order.add("attempt:"+call.getArgument(0));return GatewayAttemptWorker.Step.IDLE;});
        when(webhook.runNext(anyString())).thenAnswer(call->{order.add("inbox:"+call.getArgument(0));return GatewayAttemptWorker.Step.IDLE;});
        when(refund.runNext(anyString())).thenAnswer(call->{order.add("refund:"+call.getArgument(0));return GatewayAttemptWorker.Step.IDLE;});
        when(settlement.runNext(anyString())).thenAnswer(call->{order.add("settlement:"+call.getArgument(0));return GatewayAttemptWorker.Step.IDLE;});
        var all=new GatewayAttemptScheduler(registry,worker,webhook,refund,settlement,accepted::add);
        for(int poll=0;poll<4;poll++)all.dispatchDueAttempts();assertThat(accepted).hasSize(8);
        verifyNoInteractions(worker,webhook,refund,settlement);accepted.forEach(Runnable::run);
        assertThat(order).containsExactly("attempt:"+first.routeKey(),"inbox:"+first.routeKey(),"refund:"+first.routeKey(),"settlement:"+first.routeKey(),
                "attempt:"+second.routeKey(),"inbox:"+second.routeKey(),"refund:"+second.routeKey(),"settlement:"+second.routeKey());
    }

    @Test void settlementCompletionReviewAndRetryNeverExceedEightStepsInTheirOneAcceptedBatch(){
        var webhook=mock(GatewayWebhookWorker.class);var refund=mock(GatewayRefundWorker.class);var settlement=mock(GatewaySettlementWorker.class);var route=route("a");
        when(registry.workerEnabled()).thenReturn(true);when(registry.configuredRoutes()).thenReturn(List.of(route));when(registry.settlementsAllowed(route)).thenReturn(true);
        when(worker.runNext(route.routeKey())).thenReturn(GatewayAttemptWorker.Step.IDLE);when(webhook.runNext(route.routeKey())).thenReturn(GatewayAttemptWorker.Step.IDLE);
        when(refund.runNext(route.routeKey())).thenReturn(GatewayAttemptWorker.Step.IDLE);
        when(settlement.runNext(route.routeKey())).thenReturn(GatewayAttemptWorker.Step.COMPLETE,GatewayAttemptWorker.Step.REVIEW_REQUIRED,GatewayAttemptWorker.Step.RETRY);
        var all=new GatewayAttemptScheduler(registry,worker,webhook,refund,settlement,accepted::add);all.dispatchDueAttempts();all.dispatchDueAttempts();
        assertThat(accepted).hasSize(4);accepted.forEach(Runnable::run);
        verify(worker).runNext(route.routeKey());verify(webhook).runNext(route.routeKey());verify(refund).runNext(route.routeKey());
        verify(settlement,times(8)).runNext(route.routeKey());verify(registry,times(8)).settlementsAllowed(route);
    }

    @Test void disabledSettlementPermissionYieldsItsLaneWithoutStarvingAnyExistingWorker(){
        var webhook=mock(GatewayWebhookWorker.class);var refund=mock(GatewayRefundWorker.class);var settlement=mock(GatewaySettlementWorker.class);var route=route("a");
        when(registry.workerEnabled()).thenReturn(true);when(registry.configuredRoutes()).thenReturn(List.of(route));
        var order=new ArrayList<String>();
        when(worker.runNext(anyString())).thenAnswer(call->{order.add("attempt");return GatewayAttemptWorker.Step.IDLE;});
        when(webhook.runNext(anyString())).thenAnswer(call->{order.add("inbox");return GatewayAttemptWorker.Step.IDLE;});
        when(refund.runNext(anyString())).thenAnswer(call->{order.add("refund");return GatewayAttemptWorker.Step.IDLE;});
        var all=new GatewayAttemptScheduler(registry,worker,webhook,refund,settlement,accepted::add);
        for(int poll=0;poll<4;poll++)all.dispatchDueAttempts();accepted.forEach(Runnable::run);
        assertThat(order).containsExactly("attempt","inbox","refund","attempt","inbox","refund");
        verify(registry,times(2)).settlementsAllowed(route);verifyNoInteractions(settlement);
    }

    @Test void optionalTestConstructorHolesCannotSendASettlementLaneToAMissingCallbackOrRefundWorker(){
        var settlement=mock(GatewaySettlementWorker.class);var refund=mock(GatewayRefundWorker.class);var route=route("a");
        when(registry.workerEnabled()).thenReturn(true);when(registry.configuredRoutes()).thenReturn(List.of(route));when(registry.settlementsAllowed(route)).thenReturn(true);
        var order=new ArrayList<String>();
        when(worker.runNext(anyString())).thenAnswer(call->{order.add("attempt");return GatewayAttemptWorker.Step.IDLE;});
        when(refund.runNext(anyString())).thenAnswer(call->{order.add("refund");return GatewayAttemptWorker.Step.IDLE;});
        when(settlement.runNext(anyString())).thenAnswer(call->{order.add("settlement");return GatewayAttemptWorker.Step.IDLE;});
        var onlyReport=new GatewayAttemptScheduler(registry,worker,null,null,settlement,accepted::add);
        onlyReport.dispatchDueAttempts();assertThat(accepted).hasSize(2);accepted.forEach(Runnable::run);
        assertThat(order).containsExactly("attempt","settlement");accepted.clear();order.clear();
        var withRefund=new GatewayAttemptScheduler(registry,worker,null,refund,settlement,accepted::add);
        for(int poll=0;poll<3;poll++)withRefund.dispatchDueAttempts();accepted.forEach(Runnable::run);
        assertThat(order).containsExactly("attempt","refund","settlement","attempt","refund","settlement");
    }

    @Test void oneAcceptedSlotCannotStarveFourthLaneOrNextSchoolByAdvancingRejectedCandidates(){
        var webhook=mock(GatewayWebhookWorker.class);var refund=mock(GatewayRefundWorker.class);var settlement=mock(GatewaySettlementWorker.class);
        var first=route("a");var second=route("b");when(registry.workerEnabled()).thenReturn(true);
        when(registry.configuredRoutes()).thenReturn(List.of(first,second));when(registry.settlementsAllowed(any())).thenReturn(true);
        var order=new ArrayList<String>();
        when(worker.runNext(anyString())).thenAnswer(call->{order.add("attempt:"+call.getArgument(0));return GatewayAttemptWorker.Step.IDLE;});
        when(webhook.runNext(anyString())).thenAnswer(call->{order.add("inbox:"+call.getArgument(0));return GatewayAttemptWorker.Step.IDLE;});
        when(refund.runNext(anyString())).thenAnswer(call->{order.add("refund:"+call.getArgument(0));return GatewayAttemptWorker.Step.IDLE;});
        when(settlement.runNext(anyString())).thenAnswer(call->{order.add("settlement:"+call.getArgument(0));return GatewayAttemptWorker.Step.IDLE;});
        var slots=new AtomicInteger();Executor oneSlot=task->{if(slots.getAndDecrement()<=0)throw new RejectedExecutionException("Synthetic busy slot");accepted.add(task);};
        var all=new GatewayAttemptScheduler(registry,worker,webhook,refund,settlement,oneSlot);
        for(int poll=0;poll<9;poll++){slots.set(1);all.dispatchDueAttempts();}assertThat(accepted).hasSize(9);accepted.forEach(Runnable::run);
        assertThat(order).containsExactly("attempt:"+first.routeKey(),"inbox:"+first.routeKey(),"refund:"+first.routeKey(),"settlement:"+first.routeKey(),
                "attempt:"+second.routeKey(),"inbox:"+second.routeKey(),"refund:"+second.routeKey(),"settlement:"+second.routeKey(),"attempt:"+first.routeKey());
    }

    @Test void settlementPermissionIsRecheckedAtExecutionAndShutdownStillOwnsOnlyTheSharedPool(){
        var settlement=mock(GatewaySettlementWorker.class);var route=route("a");when(registry.workerEnabled()).thenReturn(true);
        when(registry.configuredRoutes()).thenReturn(List.of(route));when(registry.settlementsAllowed(route)).thenReturn(true);
        when(worker.runNext(route.routeKey())).thenReturn(GatewayAttemptWorker.Step.IDLE);
        var all=new GatewayAttemptScheduler(registry,worker,null,null,settlement,accepted::add);all.dispatchDueAttempts();
        when(registry.settlementsAllowed(route)).thenReturn(false);accepted.forEach(Runnable::run);verifyNoInteractions(settlement);
        var pool=mock(java.util.concurrent.ExecutorService.class);
        new GatewayAttemptScheduler(registry,worker,null,null,settlement,pool).shutdown();verify(pool).shutdownNow();
    }

    private static GatewayProviderRegistry.Route route(String key) {
        return new GatewayProviderRegistry.Route(key.repeat(64), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "CASHFREE", CashfreePaymentProviderAdapter.Environment.TEST, "synthetic_scheduler", "synthetic:scheduler-credentials",
                "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_ID", "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_SECRET");
    }
}
