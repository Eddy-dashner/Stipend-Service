package com.stipend.service.kafka.config;

import com.stipend.service.Enum.AttendanceStatus;
import com.stipend.service.Event.AttendanceEvent;
import com.stipend.service.Event.StudentRegisteredEvent;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The attendance service publishes with Spring's JsonSerializer, which adds a
 * __TypeId__ header naming its own classes (absent from this classpath).
 */
class AttendanceProducerCompatibilityTest {

    private static final String PRODUCER_ATTENDANCE_TYPE =
            "com.shecancode.attendence.Attendence.Event.AttendanceEvent";
    private static final String PRODUCER_REGISTRATION_TYPE =
            "com.shecancode.attendence.registration.Event.StudentRegisteredEvent";

    @Test
    void attendanceConsumerPropertiesIgnoreProducerTypeHeader() {
        Map<String, Object> config = new HashMap<>(consumerPropertiesFromApplicationYaml());
        // What @KafkaListener(properties = ...) adds for the attendance listener.
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, AttendanceEvent.class.getName());

        UUID attendanceId = UUID.randomUUID();
        String payload = """
                {"version":1,"eventId":"%s","attendanceId":"%s","studentId":"%s",
                 "cohortId":"%s","programId":"%s","attendanceDate":"2026-11-20",
                 "attendanceStatus":"LATE_PRESENT","checkInTime":"09:12:00",
                 "timestamp":"2026-11-20T07:12:00Z"}
                """.formatted(UUID.randomUUID(), attendanceId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());

        try (JsonDeserializer<Object> deserializer = new JsonDeserializer<>()) {
            deserializer.configure(config, false);
            Object value = deserializer.deserialize(
                    "attendance-events", typeHeader(PRODUCER_ATTENDANCE_TYPE), bytes(payload));

            assertThat(value).isInstanceOf(AttendanceEvent.class);
            AttendanceEvent event = (AttendanceEvent) value;
            assertThat(event.attendanceId()).isEqualTo(attendanceId);
            assertThat(event.attendanceStatus()).isEqualTo(AttendanceStatus.LATE);
            assertThat(event.attendanceDate()).isEqualTo(LocalDate.of(2026, 11, 20));
            assertThat(event.version()).isEqualTo(1);
        }
    }

    @Test
    void registrationConsumerIgnoresProducerTypeHeader() {
        var deserializer = new KafkaConsumerConfig()
                .studentRegistrationConsumerFactory("localhost:9092", "group")
                .getValueDeserializer();
        UUID studentId = UUID.randomUUID();
        String payload = """
                {"version":1,"eventId":"%s","studentId":"%s","programId":"%s",
                 "cohortId":"%s","timestamp":"2026-11-20T07:12:00Z"}
                """.formatted(UUID.randomUUID(), studentId, UUID.randomUUID(), UUID.randomUUID());

        StudentRegisteredEvent event = deserializer.deserialize(
                "student-registration-events", typeHeader(PRODUCER_REGISTRATION_TYPE), bytes(payload));

        assertThat(event).isNotNull();
        assertThat(event.studentId()).isEqualTo(studentId);
    }

    private static Map<String, Object> consumerPropertiesFromApplicationYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties all = yaml.getObject();
        String prefix = "spring.kafka.consumer.properties.";
        Map<String, Object> result = new HashMap<>();
        // YAML scalars may be Boolean, which stringPropertyNames()/getProperty() skip.
        all.forEach((key, value) -> {
            String name = key.toString();
            if (name.startsWith(prefix)) {
                result.put(name.substring(prefix.length()), String.valueOf(value));
            }
        });
        assertThat(result).containsEntry(JsonDeserializer.USE_TYPE_INFO_HEADERS, "false");
        return result;
    }

    private static RecordHeaders typeHeader(String className) {
        RecordHeaders headers = new RecordHeaders();
        headers.add("__TypeId__", bytes(className));
        return headers;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
