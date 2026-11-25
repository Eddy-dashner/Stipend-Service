package com.stipend.service.DTO;

import java.time.Instant;

/**
 * @param paymentReference batch payment reference recorded on every stipend
 * @param paidAt           when the batch payment was made
 * @param expectedCount    optional safety check: the number of APPROVED stipends the
 *                         caller is paying. If it differs, nothing is marked paid (409).
 */
public record BulkMarkPaidStipendsRequestDTO(String paymentReference, Instant paidAt, Integer expectedCount) {
}
