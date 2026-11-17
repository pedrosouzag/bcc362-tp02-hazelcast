import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.cp.lock.FencedLock;
import java.io.*;
import java.net.*;
import java.util.Random;

public class ClusterSync {
    private final int id;
    private final boolean protegido;
    private final FencedLock lock;
    private final Random random = new Random();

    ClusterSync(HazelcastInstance hz, int id, boolean protegido) {
        this.id = id;
        this.protegido = protegido;
        // Todos os Sync usam o mesmo nome, portanto disputam o mesmo recurso.
        this.lock = hz.getCPSubsystem().getLock(Main.LOCK_R);
    }

    void executar() throws Exception {
        // Verifica o observador antes de anunciar que pode receber clientes.
        if (!"PONG".equals(Verificador.enviar("PING"))) throw new IOException("Observador indisponível.");
        try (ServerSocket servidor = new ServerSocket(6000 + id)) {
            System.out.println("SERVIDOR_PRONTO id=" + id + " porta=" + (6000 + id) + " protegido=" + protegido);
            // Um cliente por Sync: a concorrência acontece entre as JVMs.
            while (true) {
                try (Socket socket = servidor.accept();
                     BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                     PrintWriter saida = new PrintWriter(socket.getOutputStream(), true)) {
                    String pedido = entrada.readLine();
                    if (pedido == null || !pedido.matches("WRITE " + id + "-[1-9][0-9]*")) {
                        saida.println("ERROR pedido_invalido");
                        continue;
                    }
                    String requestId = pedido.substring(6);
                    try {
                        processar(requestId);
                        saida.println("COMMITTED " + requestId);
                        if (saida.checkError()) throw new IOException("Não foi possível enviar COMMITTED.");
                        log("COMMITTED", requestId, "");
                    } catch (IOException | RuntimeException erro) {
                        saida.println("ERROR " + requestId);
                        System.err.println("ERRO request_id=" + requestId + " motivo=" + erro.getMessage());
                    }
                }
            }
        }
    }

    private void acquire(String requestId) {
        long inicio = System.nanoTime();
        if (protegido) lock.lock();
        log("ACQUIRE", requestId, "espera_ns=" + (System.nanoTime() - inicio));
    }

    private void release(String requestId) {
        if (protegido) lock.unlock();
        log("RELEASE", requestId, "");
    }

    private void processar(String requestId) throws IOException, InterruptedException {
        log("REQUEST", requestId, "");
        acquire(requestId);
        try {
            // O observador registra a entrada; quem autoriza é somente o lock.
            Verificador.registrar("BEGIN", id, requestId);
            try {
                int duracao = 200 + random.nextInt(801);
                log("ENTER", requestId, "duracao_ms=" + duracao);
                Thread.sleep(duracao); // R é hipotético: não gravamos dados.
            } finally {
                log("EXIT", requestId, "");
                // Confirmar END antes de liberar evita uma falsa sobreposição no registro.
                Verificador.registrar("END", id, requestId);
            }
        } finally {
            // Mesmo se a simulação falhar, devolvemos o lock adquirido nesta thread.
            release(requestId);
        }
    }

    private void log(String evento, String requestId, String detalhe) {
        System.out.println(evento + " sync=" + id + " request_id=" + requestId
            + " recurso=R protegido=" + protegido + " " + detalhe);
    }
}
