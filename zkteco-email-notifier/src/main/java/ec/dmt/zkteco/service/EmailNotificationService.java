package ec.dmt.zkteco.service;

import ec.dmt.zkteco.domain.AuthenticationEvent;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
public class EmailNotificationService implements BatchSink {
    private final JavaMailSender sender;
    private final String from = env("SMTP_FROM", "");
    private final JdbcTemplate jdbc;
    @Value("${zkteco.institution.name:Institución Educativa}") private String institutionName;
    @Value("${zkteco.institution.footer:Este mensaje fue generado automáticamente. No responda a este correo.}") private String institutionFooter;
    public EmailNotificationService(JavaMailSender sender, JdbcTemplate jdbc) { this.sender = sender; this.jdbc = jdbc; }
    @Override public void send(List<AuthenticationEvent> events) {
        for (AuthenticationEvent event : events) {
            List<String> recipients = jdbc.query("SELECT g.email FROM guardian g JOIN student s ON s.id=g.student_id WHERE s.biometric_user_id=? AND s.active=true AND g.active=true AND g.email IS NOT NULL AND g.email<>''", (rs, row) -> rs.getString(1), event.userId());
            if (recipients.isEmpty()) { System.out.println("[EMAIL_OMITIDO] No hay representante para userId=" + event.userId()); continue; }
            if (from.isBlank()) { System.out.println("[EMAIL_SIMULADO] SMTP_FROM no configurado; userId=" + event.userId()); continue; }
            for (String to : recipients) {
            Long eventDbId = jdbc.query("SELECT id FROM attendance_event WHERE biometric_user_id=? AND device_serial=? ORDER BY event_time DESC LIMIT 1", rs -> rs.next() ? rs.getLong(1) : null, event.userId(), event.deviceSerial());
            Long guardianId = jdbc.query("SELECT g.id FROM guardian g JOIN student s ON s.id=g.student_id WHERE s.biometric_user_id=? AND g.email=? LIMIT 1", rs -> rs.next() ? rs.getLong(1) : null, event.userId(), to);
            if (eventDbId == null || guardianId == null) { System.out.println("[NOTIFICATION_OMITIDA] No se encontró evento/representante para userId=" + event.userId()); continue; }
            jdbc.update("INSERT INTO notification (attendance_event_id,guardian_id,status,attempts) VALUES (?,?, 'PENDING',0) ON CONFLICT (attendance_event_id,guardian_id) DO NOTHING", eventDbId, guardianId);
            String current = jdbc.query("SELECT status FROM notification WHERE attendance_event_id=? AND guardian_id=?", rs -> rs.next() ? rs.getString(1) : null, eventDbId, guardianId);
            if ("SENT".equalsIgnoreCase(current)) { System.out.println("[EMAIL_DUPLICADO_OMITIDO] userId=" + event.userId() + " to=" + to); continue; }
            jdbc.update("UPDATE notification SET attempts=attempts+1,status='PENDING',error_message=NULL WHERE attendance_event_id=? AND guardian_id=?", eventDbId, guardianId);
            try {
                var mail = sender.createMimeMessage(); var helper = new MimeMessageHelper(mail, true, "UTF-8");
                StudentInfo student = jdbc.query("SELECT s.first_name,s.last_name,c.name,p.name FROM student s JOIN parallel p ON p.id=s.parallel_id JOIN course c ON c.id=p.course_id WHERE s.biometric_user_id=?", rs -> rs.next() ? new StudentInfo(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)) : null, event.userId());
                if (student == null) throw new IllegalStateException("Estudiante no encontrado");
                String when = event.authenticatedAt().atZoneSameInstant(ZoneId.of("America/Guayaquil")).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
                helper.setFrom(from); helper.setTo(to); helper.setSubject("Ingreso registrado - " + student.fullName());
                helper.setText("<div style='font-family:Arial,sans-serif;background:#f4f7fb;padding:32px'><div style='max-width:560px;margin:auto;background:#fff;border-radius:12px;overflow:hidden'><div style='background:#172b4d;color:#fff;padding:24px'><h2 style='margin:0'>" + esc(institutionName) + "</h2><p style='margin:8px 0 0;opacity:.85'>Notificación de ingreso estudiantil</p></div><div style='padding:28px;color:#26344d'><p>Estimado representante:</p><p>Informamos que el siguiente estudiante ingresó correctamente a la institución.</p><div style='background:#f4f7fb;border-radius:8px;padding:18px;margin:20px 0'><p><b>Estudiante:</b> " + esc(student.fullName()) + "</p><p><b>Curso:</b> " + esc(student.course()) + "</p><p><b>Paralelo:</b> " + esc(student.parallel()) + "</p><p><b>Fecha y hora:</b> " + when + "</p><p><b>Dispositivo:</b> " + esc(event.deviceSerial()) + "</p></div><p style='font-size:13px;color:#76839a'>" + esc(institutionFooter) + "</p></div></div></div>", true);
                sender.send(mail);
                jdbc.update("UPDATE notification SET status='SENT',sent_at=CURRENT_TIMESTAMP,error_message=NULL WHERE attendance_event_id=? AND guardian_id=?", eventDbId, guardianId);
                System.out.println("[EMAIL_ENVIADO] userId=" + event.userId() + " to=" + to);
            } catch (Exception ex) {
                jdbc.update("UPDATE notification SET status='FAILED',error_message=? WHERE attendance_event_id=? AND guardian_id=?", ex.getMessage(), eventDbId, guardianId);
                System.out.println("[EMAIL_ERROR] userId=" + event.userId() + " error=" + ex.getMessage());
            }
            }
        }
    }
    private static String env(String key, String fallback) { String v=System.getenv(key); return v==null?fallback:v; }
    private static String esc(String value) { return value == null ? "" : value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }
    private record StudentInfo(String first, String last, String course, String parallel) { String fullName(){ return first + " " + last; } }
}
