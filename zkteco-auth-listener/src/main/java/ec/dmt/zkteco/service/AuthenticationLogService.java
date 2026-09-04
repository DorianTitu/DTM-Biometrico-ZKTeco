package ec.dmt.zkteco.service;

import ec.dmt.zkteco.domain.AuthenticationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
public class AuthenticationLogService {
    private static final Logger log = LoggerFactory.getLogger(AuthenticationLogService.class);
    private final AtomicLong sequence = new AtomicLong();
    private final AuthEventPublisher publisher;
    private final JdbcTemplate jdbc;
    public AuthenticationLogService(AuthEventPublisher publisher, JdbcTemplate jdbc) { this.publisher = publisher; this.jdbc = jdbc; }

    public AuthenticationEvent record(String deviceSerial, String userId, String dateTime,
                                      int verifyType, int status) {
        AuthenticationEvent event = new AuthenticationEvent(
                sequence.incrementAndGet(), deviceSerial, userId, parseDateTime(dateTime), verifyType, status);
        jdbc.update("INSERT INTO attendance_event (student_id, biometric_user_id, device_serial, event_time) " +
                        "VALUES ((SELECT id FROM student WHERE biometric_user_id=? AND active=true),?,?,?) " +
                        "ON CONFLICT (biometric_user_id,device_serial,event_time) DO NOTHING",
                userId, userId, deviceSerial, event.authenticatedAt());
        log.info("Hola usuario id: {}", event.userId());
        publisher.publish(event);
        log.info("[AUTH_OK] eventId={} userId={} device={} authenticatedAt={} verifyType={} status={}",
                event.eventId(), event.userId(), event.deviceSerial(), event.authenticatedAt(),
                event.verifyType(), event.status());
        return event;
    }

    private OffsetDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value.replace(' ', 'T')).atOffset(ZoneOffset.of("-05:00"));
        } catch (RuntimeException ex) {
            return OffsetDateTime.now(ZoneOffset.of("-05:00"));
        }
    }
}
