package com.stipend.service.Enum;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttendanceStatusTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsAttendanceServiceStatusNames() throws Exception {
        assertThat(read("LATE_PRESENT")).isEqualTo(AttendanceStatus.LATE);
        assertThat(read("ABSENT_COMMUNICATED")).isEqualTo(AttendanceStatus.ABSENT);
        assertThat(read("PRESENT")).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(read("ABSENT")).isEqualTo(AttendanceStatus.ABSENT);
    }

    @Test
    void acceptsOwnStatusNames() throws Exception {
        for (AttendanceStatus status : AttendanceStatus.values()) {
            assertThat(read(status.name())).isEqualTo(status);
        }
    }

    @Test
    void rejectsUnknownStatus() {
        assertThatThrownBy(() -> read("ON_HOLIDAY"))
                .isInstanceOf(ValueInstantiationException.class);
    }

    private AttendanceStatus read(String value) throws Exception {
        return mapper.readValue("\"" + value + "\"", AttendanceStatus.class);
    }
}
