package com.stipend.service.DTO;

import com.stipend.service.Enum.StipendStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outcome of a cohort-period bulk action.
 *
 * @param updated            stipends moved to {@code newStatus} by this request
 * @param notUpdatedByStatus remaining stipends in the cohort period, counted by their
 *                           (unchanged) status, e.g. already PAID or NEEDS_RECALCULATION
 */
public record BulkStipendActionResultDTO(
        UUID cohortId,
        int year,
        int month,
        StipendStatus newStatus,
        int updatedCount,
        List<MonthlyStipendResponseDTO> updated,
        Map<StipendStatus, Long> notUpdatedByStatus
) {
}
