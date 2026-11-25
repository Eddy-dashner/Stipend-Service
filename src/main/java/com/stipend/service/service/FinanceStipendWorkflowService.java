package com.stipend.service.service;

import com.stipend.service.DTO.ApproveStipendRequestDTO;
import com.stipend.service.DTO.BulkApproveStipendsRequestDTO;
import com.stipend.service.DTO.BulkMarkPaidStipendsRequestDTO;
import com.stipend.service.DTO.BulkStipendActionResultDTO;
import com.stipend.service.DTO.MarkPaidStipendRequestDTO;
import com.stipend.service.DTO.MonthlyStipendResponseDTO;
import com.stipend.service.Enum.StipendStatus;
import com.stipend.service.Exception.FinancePrincipalRequiredException;
import com.stipend.service.Exception.InvalidStipendTransitionException;
import com.stipend.service.Exception.ResourceNotFoundException;
import com.stipend.service.Model.MonthlyStipend;
import com.stipend.service.Repository.MonthlyStipendRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FinanceStipendWorkflowService {

    private static final int MAX_APPROVAL_COMMENT_LENGTH = 500;
    private static final int MAX_PAYMENT_REFERENCE_LENGTH = 100;

    private final MonthlyStipendRepository monthlyStipendRepository;
    private final FinanceStipendReadService financeStipendReadService;

    public FinanceStipendWorkflowService(
            MonthlyStipendRepository monthlyStipendRepository,
            FinanceStipendReadService financeStipendReadService
    ) {
        this.monthlyStipendRepository = monthlyStipendRepository;
        this.financeStipendReadService = financeStipendReadService;
    }

    @Transactional
    public MonthlyStipendResponseDTO approve(
            UUID stipendId,
            String approver,
            ApproveStipendRequestDTO request
    ) {
        requireFinanceUser(approver);
        String comment = validateComment(request == null ? null : request.comment());

        MonthlyStipend stipend = getForUpdate(stipendId);
        requireStatus(stipend, StipendStatus.CALCULATED, StipendStatus.APPROVED);
        applyApproval(stipend, approver, comment, Instant.now());
        monthlyStipendRepository.save(stipend);
        return financeStipendReadService.getStipend(stipendId);
    }

    @Transactional
    public MonthlyStipendResponseDTO markPaid(
            UUID stipendId,
            String financeUser,
            MarkPaidStipendRequestDTO request
    ) {
        requireFinanceUser(financeUser);
        if (request == null) {
            throw new IllegalArgumentException("payment request body is required");
        }
        validatePayment(request.paymentReference(), request.paidAt());

        MonthlyStipend stipend = getForUpdate(stipendId);
        requireStatus(stipend, StipendStatus.APPROVED, StipendStatus.PAID);
        applyPayment(stipend, request.paymentReference(), request.paidAt(), Instant.now());
        monthlyStipendRepository.save(stipend);
        return financeStipendReadService.getStipend(stipendId);
    }

    /**
     * Approves every CALCULATED stipend of a cohort for one month, atomically. Stipends
     * in any other status are left unchanged and reported in the result.
     */
    @Transactional
    public BulkStipendActionResultDTO approveCohortPeriod(
            UUID cohortId,
            int year,
            int month,
            String approver,
            BulkApproveStipendsRequestDTO request
    ) {
        requireFinanceUser(approver);
        String comment = validateComment(request == null ? null : request.comment());

        List<MonthlyStipend> stipends = monthlyStipendRepository.findCohortPeriodByStatusForUpdate(
                cohortId, year, month, StipendStatus.CALCULATED);
        requireExpectedCount(request == null ? null : request.expectedCount(), stipends.size(),
                StipendStatus.CALCULATED);

        Instant now = Instant.now();
        stipends.forEach(stipend -> applyApproval(stipend, approver, comment, now));
        monthlyStipendRepository.saveAll(stipends);
        return bulkResult(cohortId, year, month, StipendStatus.APPROVED, stipends);
    }

    /**
     * Marks every APPROVED stipend of a cohort for one month as PAID with one batch
     * payment reference, atomically. Other statuses are left unchanged and reported.
     */
    @Transactional
    public BulkStipendActionResultDTO markCohortPeriodPaid(
            UUID cohortId,
            int year,
            int month,
            String financeUser,
            BulkMarkPaidStipendsRequestDTO request
    ) {
        requireFinanceUser(financeUser);
        if (request == null) {
            throw new IllegalArgumentException("payment request body is required");
        }
        validatePayment(request.paymentReference(), request.paidAt());

        List<MonthlyStipend> stipends = monthlyStipendRepository.findCohortPeriodByStatusForUpdate(
                cohortId, year, month, StipendStatus.APPROVED);
        requireExpectedCount(request.expectedCount(), stipends.size(), StipendStatus.APPROVED);

        Instant now = Instant.now();
        stipends.forEach(stipend ->
                applyPayment(stipend, request.paymentReference(), request.paidAt(), now));
        monthlyStipendRepository.saveAll(stipends);
        return bulkResult(cohortId, year, month, StipendStatus.PAID, stipends);
    }

    private BulkStipendActionResultDTO bulkResult(
            UUID cohortId,
            int year,
            int month,
            StipendStatus newStatus,
            List<MonthlyStipend> changed
    ) {
        Set<UUID> changedIds = changed.stream().map(MonthlyStipend::getId).collect(Collectors.toSet());
        List<MonthlyStipendResponseDTO> cohortPeriod =
                financeStipendReadService.getCohortStipends(cohortId, year, month, null);

        List<MonthlyStipendResponseDTO> updated = cohortPeriod.stream()
                .filter(stipend -> changedIds.contains(stipend.id()))
                .toList();
        Map<StipendStatus, Long> notUpdated = cohortPeriod.stream()
                .filter(stipend -> !changedIds.contains(stipend.id()))
                .collect(Collectors.groupingBy(
                        MonthlyStipendResponseDTO::status,
                        () -> new EnumMap<>(StipendStatus.class),
                        Collectors.counting()));

        return new BulkStipendActionResultDTO(
                cohortId, year, month, newStatus, changed.size(), updated, notUpdated);
    }

    private static void applyApproval(MonthlyStipend stipend, String approver, String comment, Instant now) {
        stipend.setStatus(StipendStatus.APPROVED);
        stipend.setApprovedBy(approver);
        stipend.setApprovalComment(comment);
        stipend.setApprovedAt(now);
        stipend.setUpdatedAt(now);
    }

    private static void applyPayment(MonthlyStipend stipend, String reference, Instant paidAt, Instant now) {
        stipend.setStatus(StipendStatus.PAID);
        stipend.setPaymentReference(reference);
        stipend.setPaidAt(paidAt);
        stipend.setUpdatedAt(now);
    }

    private static String validateComment(String comment) {
        if (comment != null && comment.length() > MAX_APPROVAL_COMMENT_LENGTH) {
            throw new IllegalArgumentException("comment must not exceed 500 characters");
        }
        return comment;
    }

    private static void validatePayment(String paymentReference, Instant paidAt) {
        if (paymentReference == null || paymentReference.isBlank()) {
            throw new IllegalArgumentException("paymentReference must not be blank");
        }
        if (paymentReference.length() > MAX_PAYMENT_REFERENCE_LENGTH) {
            throw new IllegalArgumentException("paymentReference must not exceed 100 characters");
        }
        if (paidAt == null) {
            throw new IllegalArgumentException("paidAt is required");
        }
    }

    private static void requireExpectedCount(Integer expected, int actual, StipendStatus status) {
        if (expected == null) {
            return;
        }
        if (expected < 0) {
            throw new IllegalArgumentException("expectedCount must be zero or greater");
        }
        if (expected != actual) {
            throw new InvalidStipendTransitionException(
                    "Expected " + expected + " " + status + " stipends but found " + actual
                            + "; nothing was changed. Reload the cohort and try again.");
        }
    }

    private MonthlyStipend getForUpdate(UUID stipendId) {
        return monthlyStipendRepository.findByIdForUpdate(stipendId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Monthly stipend not found: " + stipendId
                ));
    }

    private void requireStatus(MonthlyStipend stipend, StipendStatus required, StipendStatus target) {
        if (stipend.getStatus() != required) {
            throw new InvalidStipendTransitionException(
                    "Cannot transition stipend from " + stipend.getStatus() + " to " + target
                            + "; expected " + required
            );
        }
    }

    private void requireFinanceUser(String financeUser) {
        if (financeUser == null || financeUser.isBlank()) {
            throw new FinancePrincipalRequiredException();
        }
    }
}
