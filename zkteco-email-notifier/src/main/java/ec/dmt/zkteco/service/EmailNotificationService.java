package ec.dmt.zkteco.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Arrays;

@Service
public class EmailNotificationService {
    private final JdbcTemplate jdbc;
    private final JavaMailSender sender;
    private final boolean enabled;
    private final String from;
    private final String institutionName;
    private final List<String> testRecipients;

    public EmailNotificationService(JdbcTemplate jdbc, JavaMailSender sender,
                                    @Value("${MAIL_ENABLED:false}") boolean enabled,
                                    @Value("${SMTP_FROM:}") String from,
                                    @Value("${INSTITUTION_NAME:Colegio Técnico Salesiano Don Bosco}") String institutionName,
                                    @Value("${MAIL_TEST_RECIPIENTS:}") String testRecipients) {
        this.jdbc = jdbc; this.sender = sender; this.enabled = enabled; this.from = from; this.institutionName = institutionName;
        this.testRecipients = Arrays.stream(testRecipients.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    @Scheduled(fixedDelayString = "${dmt.notifications.poll-ms:10000}")
    public void processPendingNotifications() {
        if (!enabled || from.isBlank()) return;
        List<Notification> pending = jdbc.query("""
            WITH claimed AS (
              SELECT id FROM attendance_notifications WHERE status='PENDING'
              ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 20
            )
            UPDATE attendance_notifications n SET status='PROCESSING',attempts=attempts+1
            FROM claimed WHERE n.id=claimed.id
            RETURNING n.id,n.event_type,n.attendance_date,n.student_id
            """, (rs, row) -> new Notification(rs.getLong("id"), rs.getString("event_type"), rs.getObject("attendance_date", java.time.LocalDate.class), rs.getLong("student_id")));
        for (Notification notification : pending) send(notification);
    }

    private void send(Notification notification) {
        try {
            Student student = jdbc.query("""
                SELECT s.first_names,s.last_names,s.representative_email,c.name,d.first_entry_at,d.last_exit_at,d.status
                FROM students s JOIN courses c ON c.id=s.course_id
                JOIN daily_attendance d ON d.student_id=s.id AND d.attendance_date=?
                WHERE s.id=? AND s.active
                """, rs -> rs.next() ? new Student(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getObject(5, java.time.OffsetDateTime.class),rs.getObject(6, java.time.OffsetDateTime.class),rs.getString(7)) : null,
                    notification.date(), notification.studentId());
            if (student == null || (testRecipients.isEmpty() && (student.email() == null || student.email().isBlank()))) {
                jdbc.update("UPDATE attendance_notifications SET status='SENT',sent_at=now(),last_error='Sin correo de representante' WHERE id=?", notification.id());
                return;
            }
            var eventTime = "ENTRY".equals(notification.type()) ? student.entry() : student.exit();
            String when = eventTime == null ? notification.date().toString() : eventTime.atZoneSameInstant(ZoneId.of("America/Guayaquil")).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
            boolean late = "ENTRY".equals(notification.type()) && "LATE".equals(student.status());
            String action = late ? "ha ingresado con atraso" : ("ENTRY".equals(notification.type()) ? "ha ingresado" : "ha salido");
            String subject = late ? "Aviso de atraso" : ("ENTRY".equals(notification.type()) ? "Ingreso registrado" : "Salida registrada");
            var mail = sender.createMimeMessage(); var helper = new MimeMessageHelper(mail, false, "UTF-8");
            helper.setFrom(from); helper.setTo((testRecipients.isEmpty() ? List.of(student.email()) : testRecipients).toArray(String[]::new));
            helper.setSubject(subject + " - " + student.fullName());
            String accent = late ? "#c76b2b" : ("ENTRY".equals(notification.type()) ? "#1c5b91" : "#b38a24");
            String title = late ? "Ingreso fuera de horario" : ("ENTRY".equals(notification.type()) ? "Ingreso registrado" : "Salida registrada");
            helper.setText("<div style='margin:0;background:#f4f7fb;padding:28px 12px;font-family:Arial,Helvetica,sans-serif;color:#172b4d'>" +
                    "<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='max-width:620px;margin:auto;background:#fff;border-radius:16px;overflow:hidden;border:1px solid #e2e8f0'>" +
                    "<tr><td style='background:#123f6d;padding:26px 30px;color:#fff'><div style='font-size:12px;letter-spacing:2px;text-transform:uppercase;color:#f1c453;font-weight:bold'>Colegio Técnico Salesiano</div><div style='font-size:24px;font-weight:bold;margin-top:7px'>Don Bosco</div></td></tr>" +
                    "<tr><td style='padding:30px'><div style='display:inline-block;background:" + accent + ";color:#fff;border-radius:20px;padding:7px 13px;font-size:12px;font-weight:bold;text-transform:uppercase;letter-spacing:.7px'>" + title + "</div>" +
                    "<h1 style='font-size:26px;line-height:1.2;margin:20px 0 10px;color:#123f6d'>" + esc(student.fullName()) + "</h1>" +
                    "<p style='font-size:16px;line-height:1.6;margin:0 0 22px;color:#425466'>Estimado representante, le informamos que el estudiante " + esc(action) + " en la institución.</p>" +
                    "<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='background:#eef5fb;border-radius:12px'><tr><td style='padding:18px 20px'><div style='font-size:12px;color:#60758b;text-transform:uppercase;letter-spacing:1px'>Curso</div><div style='font-size:17px;font-weight:bold;margin-top:5px;color:#172b4d'>" + esc(student.course()) + "</div></td><td style='padding:18px 20px'><div style='font-size:12px;color:#60758b;text-transform:uppercase;letter-spacing:1px'>Fecha y hora</div><div style='font-size:17px;font-weight:bold;margin-top:5px;color:#172b4d'>" + when + "</div></td></tr></table>" +
                    (late ? "<p style='margin:22px 0 0;padding:14px 16px;border-left:4px solid #c76b2b;background:#fff5ed;color:#82451d;font-size:14px;line-height:1.5'>La marcación se registró después del horario puntual de entrada.</p>" : "") +
                    "</td></tr><tr><td style='background:#f8fafc;padding:20px 30px;color:#718096;font-size:12px;line-height:1.5'>Mensaje automático del sistema de control de acceso.<br/>Por favor, no responda a este correo.</td></tr></table></div>", true);
            sender.send(mail);
            jdbc.update("UPDATE attendance_notifications SET status='SENT',sent_at=now(),last_error=NULL WHERE id=?", notification.id());
        } catch (Exception ex) {
            jdbc.update("UPDATE attendance_notifications SET status='FAILED',last_error=? WHERE id=?", ex.getMessage(), notification.id());
        }
    }

    private static String esc(String value) { return value == null ? "" : value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }
    private record Notification(long id, String type, java.time.LocalDate date, long studentId) { }
    private record Student(String first, String last, String email, String course, java.time.OffsetDateTime entry, java.time.OffsetDateTime exit, String status) { String fullName(){ return last + " " + first; } }
}
