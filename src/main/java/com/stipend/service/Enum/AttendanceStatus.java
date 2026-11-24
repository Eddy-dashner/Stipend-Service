package com.stipend.service.Enum;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AttendanceStatus {
    PRESENT,
    ABSENT,
    LATE,
    EXCUSED,
    APPROVED_HALF_DAY;

    /**
     * Maps status names published by the attendance service onto this service's
     * stipend statuses. Unknown values are rejected so the event goes to the DLT.
     * <ul>
     *   <li>{@code LATE_PRESENT} → {@link #LATE} (unauthorized half-day loss)</li>
     *   <li>{@code ABSENT_COMMUNICATED} → {@link #ABSENT}: the student notified the
     *       programme, but that is not a Program-Manager approval, so it remains an
     *       unauthorized full-day absence. Approved absences must arrive as
     *       {@code EXCUSED}.</li>
     * </ul>
     */
    @JsonCreator
    public static AttendanceStatus fromEventValue(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toUpperCase()) {
            case "LATE_PRESENT" -> LATE;
            case "ABSENT_COMMUNICATED" -> ABSENT;
            default -> valueOf(value.trim().toUpperCase());
        };
    }
}
