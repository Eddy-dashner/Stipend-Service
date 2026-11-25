package com.stipend.service.DTO;

/**
 * @param comment       optional approval comment applied to every stipend in the batch
 * @param expectedCount optional safety check: the number of CALCULATED stipends the
 *                      caller reviewed. If it differs, nothing is approved (409).
 */
public record BulkApproveStipendsRequestDTO(String comment, Integer expectedCount) {
}
