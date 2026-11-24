package com.stipend.service.service;

import com.stipend.service.DTO.BulkApproveStipendsRequestDTO;
import com.stipend.service.DTO.BulkMarkPaidStipendsRequestDTO;
import com.stipend.service.DTO.BulkStipendActionResultDTO;
import com.stipend.service.DTO.MonthlyStipendResponseDTO;
import com.stipend.service.Enum.StipendStatus;
import com.stipend.service.Exception.FinancePrincipalRequiredException;
import com.stipend.service.Exception.InvalidStipendTransitionException;
import com.stipend.service.Model.MonthlyStipend;
import com.stipend.service.Repository.MonthlyStipendRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FinanceStipendBulkWorkflowServiceTest {

    private static final UUID COHORT = UUID.randomUUID();
    private static final int YEAR = 2026;
    private static final int MONTH = 10;

    private MonthlyStipendRepository repository;
    private FinanceStipendReadService readService;
    private FinanceStipendWorkflowService service;

    @BeforeEach
    void setUp() {
        repository = mock(MonthlyStipendRepository.class);
        readService = mock(FinanceStipendReadService.class);
        service = new FinanceStipendWorkflowService(repository, readService);
    }

    @Test
    void approvesAllCalculatedStipendsAndReportsTheRest() {
        MonthlyStipend first = stipend(StipendStatus.CALCULATED);
        MonthlyStipend second = stipend(StipendStatus.CALCULATED);
        when(repository.findCohortPeriodByStatusForUpdate(COHORT, YEAR, MONTH, StipendStatus.CALCULATED))
                .thenReturn(List.of(first, second));
        UUID paidId = UUID.randomUUID();
        UUID recalcId = UUID.randomUUID();
        when(readService.getCohortStipends(COHORT, YEAR, MONTH, null)).thenReturn(List.of(
                response(first.getId(), StipendStatus.APPROVED),
                response(second.getId(), StipendStatus.APPROVED),
                response(paidId, StipendStatus.PAID),
                response(recalcId, StipendStatus.NEEDS_RECALCULATION)));

        BulkStipendActionResultDTO result = service.approveCohortPeriod(
                COHORT, YEAR, MONTH, "finance@igire.test",
                new BulkApproveStipendsRequestDTO("October reviewed", 2));

        for (MonthlyStipend stipend : List.of(first, second)) {
            assertThat(stipend.getStatus()).isEqualTo(StipendStatus.APPROVED);
            assertThat(stipend.getApprovedBy()).isEqualTo("finance@igire.test");
            assertThat(stipend.getApprovalComment()).isEqualTo("October reviewed");
            assertThat(stipend.getApprovedAt()).isNotNull();
        }
        verify(repository).saveAll(List.of(first, second));
        assertThat(result.updatedCount()).isEqualTo(2);
        assertThat(result.newStatus()).isEqualTo(StipendStatus.APPROVED);
        assertThat(result.updated()).extracting(MonthlyStipendResponseDTO::id)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(result.notUpdatedByStatus())
                .containsEntry(StipendStatus.PAID, 1L)
                .containsEntry(StipendStatus.NEEDS_RECALCULATION, 1L)
                .hasSize(2);
    }

    @Test
    void approvalWithoutBodyApprovesWithoutComment() {
        MonthlyStipend stipend = stipend(StipendStatus.CALCULATED);
        when(repository.findCohortPeriodByStatusForUpdate(COHORT, YEAR, MONTH, StipendStatus.CALCULATED))
                .thenReturn(List.of(stipend));

        service.approveCohortPeriod(COHORT, YEAR, MONTH, "finance@igire.test", null);

        assertThat(stipend.getStatus()).isEqualTo(StipendStatus.APPROVED);
        assertThat(stipend.getApprovalComment()).isNull();
    }

    @Test
    void expectedCountMismatchChangesNothing() {
        MonthlyStipend stipend = stipend(StipendStatus.CALCULATED);
        when(repository.findCohortPeriodByStatusForUpdate(COHORT, YEAR, MONTH, StipendStatus.CALCULATED))
                .thenReturn(List.of(stipend));

        assertThatThrownBy(() -> service.approveCohortPeriod(
                COHORT, YEAR, MONTH, "finance@igire.test", new BulkApproveStipendsRequestDTO(null, 3)))
                .isInstanceOf(InvalidStipendTransitionException.class)
                .hasMessageContaining("Expected 3 CALCULATED stipends but found 1");

        assertThat(stipend.getStatus()).isEqualTo(StipendStatus.CALCULATED);
        verify(repository, never()).saveAll(any());
    }

    @Test
    void marksAllApprovedStipendsPaidWithBatchReference() {
        MonthlyStipend first = stipend(StipendStatus.APPROVED);
        MonthlyStipend second = stipend(StipendStatus.APPROVED);
        when(repository.findCohortPeriodByStatusForUpdate(COHORT, YEAR, MONTH, StipendStatus.APPROVED))
                .thenReturn(List.of(first, second));
        Instant paidAt = Instant.parse("2026-11-05T09:00:00Z");

        BulkStipendActionResultDTO result = service.markCohortPeriodPaid(
                COHORT, YEAR, MONTH, "finance@igire.test",
                new BulkMarkPaidStipendsRequestDTO("MTN-BATCH-2026-10", paidAt, null));

        for (MonthlyStipend stipend : List.of(first, second)) {
            assertThat(stipend.getStatus()).isEqualTo(StipendStatus.PAID);
            assertThat(stipend.getPaymentReference()).isEqualTo("MTN-BATCH-2026-10");
            assertThat(stipend.getPaidAt()).isEqualTo(paidAt);
        }
        assertThat(result.updatedCount()).isEqualTo(2);
        assertThat(result.newStatus()).isEqualTo(StipendStatus.PAID);
    }

    @Test
    void markPaidValidatesBeforeLockingAnything() {
        assertThatThrownBy(() -> service.markCohortPeriodPaid(
                COHORT, YEAR, MONTH, "finance@igire.test", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.markCohortPeriodPaid(
                COHORT, YEAR, MONTH, "finance@igire.test",
                new BulkMarkPaidStipendsRequestDTO(" ", Instant.now(), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.markCohortPeriodPaid(
                COHORT, YEAR, MONTH, "finance@igire.test",
                new BulkMarkPaidStipendsRequestDTO("ref", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.markCohortPeriodPaid(
                COHORT, YEAR, MONTH, null,
                new BulkMarkPaidStipendsRequestDTO("ref", Instant.now(), null)))
                .isInstanceOf(FinancePrincipalRequiredException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void emptyCohortPeriodIsANoOp() {
        when(repository.findCohortPeriodByStatusForUpdate(COHORT, YEAR, MONTH, StipendStatus.APPROVED))
                .thenReturn(List.of());

        BulkStipendActionResultDTO result = service.markCohortPeriodPaid(
                COHORT, YEAR, MONTH, "finance@igire.test",
                new BulkMarkPaidStipendsRequestDTO("ref", Instant.now(), 0));

        assertThat(result.updatedCount()).isZero();
        assertThat(result.updated()).isEmpty();
    }

    private static MonthlyStipend stipend(StipendStatus status) {
        return MonthlyStipend.builder()
                .id(UUID.randomUUID())
                .cohortId(COHORT)
                .stipendYear(YEAR)
                .stipendMonth(MONTH)
                .status(status)
                .build();
    }

    private static MonthlyStipendResponseDTO response(UUID id, StipendStatus status) {
        return new MonthlyStipendResponseDTO(
                id, UUID.randomUUID(), "First", "Last", COHORT, null, null, null,
                YEAR, MONTH, null, null, null, null, null, null, null, null,
                null, null, null, "RWF", status, null, null, null, null, null, null, null);
    }
}
