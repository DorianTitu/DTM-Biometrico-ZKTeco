package ec.dmt.zkteco.service;
import org.springframework.beans.factory.annotation.Autowired;
import ec.dmt.zkteco.config.RabbitConfig; import ec.dmt.zkteco.domain.AuthenticationEvent; import org.springframework.amqp.rabbit.annotation.RabbitListener; import org.springframework.scheduling.annotation.Scheduled; import org.springframework.stereotype.Service; import java.util.ArrayList; import java.util.List; import java.util.concurrent.ConcurrentLinkedQueue;
@Service public class AuthEventConsumer { private static final int MAX_BATCH=500; private final ConcurrentLinkedQueue<AuthenticationEvent> pending=new ConcurrentLinkedQueue<>(); private final BatchSink sink;
 @Autowired public AuthEventConsumer(EmailNotificationService sink){this.sink=sink;} public AuthEventConsumer(BatchSink sink){this.sink=sink;}
 @RabbitListener(queues=RabbitConfig.QUEUE) public void consume(AuthenticationEvent e){ pending.add(e); if(pending.size()>=MAX_BATCH) flush(); }
 @Scheduled(fixedDelayString="${zkteco.batch.flush-ms:10000}") public void scheduledFlush(){flush();}
 public synchronized void flush(){ List<AuthenticationEvent> batch=new ArrayList<>(MAX_BATCH); while(batch.size()<MAX_BATCH){AuthenticationEvent e=pending.poll(); if(e==null) break; batch.add(e);} if(batch.isEmpty()) return; System.out.println("[LOTE] Lote preparado: "+batch.size()+" eventos"); sink.send(batch); if(!pending.isEmpty()) flush(); }
}
