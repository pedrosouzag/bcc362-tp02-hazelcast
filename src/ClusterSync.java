import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.cp.lock.FencedLock;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.*;
import java.util.Random;

/**
    Atende os pedidos do cliente: pega o lock, finge que escreve em R, solta o lock e responde COMMITTED.
    Cada passo importante e anotado em logs/sync-ID.log, no formato lido pelo Verificador:
    hora;sync;pedido;evento;fence

    Eventos de cada pedido, na ordem:
    REQUEST    o pedido chegou ao cluster sync (ainda nao tem fence, entao fica -1)
    ACQUIRE    o lock foi conseguido (a espera acabou) e ja temos o fence
    ENTER      comeca a regiao critica
    EXIT       termina a regiao critica
    RELEASE    o lock foi solto
    COMMITTED  a resposta foi enviada ao cliente
 */
public class ClusterSync {

    static final Random rnd = new Random();

    // fica esperando clientes ligarem na porta 6000 + id
    static void servir(HazelcastInstance hz, int id) throws Exception {
        // o lock "R" fica guardado no Hazelcast: todos os cluster sync disputam o mesmo
        FencedLock lock = hz.getCPSubsystem().getLock(Main.LOCK_R);
        Files.createDirectories(Path.of("logs"));   // cria a pasta de logs se nao existir
        Files.deleteIfExists(Path.of("logs/sync-" + id + ".log"));   // comeca um log novo a cada execucao

        // ServerSocket = abre a porta e fica esperando alguem ligar
        try (ServerSocket servidor = new ServerSocket(6000 + id)) {
            System.out.println("SYNC_PRONTO id=" + id + " porta=" + (6000 + id));
            while (true) {
                Socket socket = servidor.accept();   // espera um cliente ligar
                // cada pedido roda numa thread propria. o lock e o unlock precisam ser na MESMA thread
                new Thread(() -> atender(socket, lock, id)).start();
            }
        }
    }

    // cuida de um pedido: ouve o cliente, usa o lock e responde
    static void atender(Socket socket, FencedLock lock, int id) {
        // o try fecha a ligacao sozinho no final
        try (socket;
             BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));   // ouvir o cliente
             PrintWriter saida = new PrintWriter(socket.getOutputStream(), true)) {                        // falar com o cliente

            String linha = entrada.readLine();            // ex.: "WRITE 3-17"
            String pedido = linha.split(" ")[1];          // pega so o "3-17"

            log(id, pedido, "REQUEST", -1);               // pedido chegou, ainda sem fence

            long fence = lock.lockAndGetFence();          // espera a vez e entra na regiao critica
            try {
                log(id, pedido, "ACQUIRE", fence);        // conseguiu o lock
                log(id, pedido, "ENTER", fence);          // comeca a regiao critica
                Thread.sleep(200 + rnd.nextInt(801));     // finge que escreve em R (200 a 1000 ms)
                log(id, pedido, "EXIT", fence);           // termina a regiao critica, ANTES de soltar o lock
            } finally {
                // finally: solta o lock mesmo se der erro, senao os outros esperariam para sempre
                lock.unlock();                            // sai da regiao critica
            }
            log(id, pedido, "RELEASE", fence);            // lock solto

            saida.println("COMMITTED " + pedido);         // avisa o cliente
            log(id, pedido, "COMMITTED", fence);          // anota que respondeu
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // escreve uma linha no log. synchronized: so uma thread escreve por vez
    // fence = -1 quando o pedido ainda nao tem fence (evento REQUEST)
    static synchronized void log(int id, String pedido, String evento, long fence) throws IOException {
        // linha no formato: hora;sync;pedido;evento;fence
        String linha = System.currentTimeMillis() + ";" + id + ";" + pedido + ";" + evento + ";" + fence + "\n";
        // CREATE = cria o arquivo se nao existir, APPEND = escreve no final sem apagar o que ja tem
        Files.writeString(Path.of("logs/sync-" + id + ".log"), linha,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
