import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.cp.lock.FencedLock;
import com.hazelcast.cp.ISemaphore;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Random;

public class ClusterSync {
    private final int id, capacidade;
    private final boolean protegido;
    private final FencedLock lock;
    private final ISemaphore semaforo;
    private final Random random = new Random();
    private final Path arquivo;

    ClusterSync(HazelcastInstance hz, int id, boolean protegido, int capacidade) throws IOException {
        this.id = id;
        this.protegido = protegido;
        this.capacidade = capacidade;
        // Mesmo nome em todos os Sync: todos disputam a mesma primitiva.
        lock = hz.getCPSubsystem().getLock(Main.LOCK_R);
        semaforo = capacidade == 0 ? null : hz.getCPSubsystem().getSemaphore("R-K" + capacidade);
        Files.createDirectories(Verificador.PASTA);
        arquivo = Verificador.PASTA.resolve("sync-" + id + ".log");
        Files.writeString(arquivo, ""); // Cada execução começa com um arquivo novo.
    }

    void executar() throws Exception {
        if (!"PONG".equals(Verificador.enviar("PING"))) throw new IOException("Observador indisponível.");
        try (ServerSocket servidor = new ServerSocket(6000 + id)) {
            System.out.println("SERVIDOR_PRONTO id=" + id + " protegido=" + protegido + " K=" + capacidade);
            // Um cliente sequencial por Sync. A concorrência está entre as JVMs.
            while (true) {
                try (Socket socket = servidor.accept();
                     BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                     PrintWriter saida = new PrintWriter(socket.getOutputStream(), true)) {
                    String mensagem = entrada.readLine();
                    if (mensagem == null || !mensagem.matches("WRITE " + id + "-[1-9][0-9]*")) {
                        saida.println("ERROR pedido_invalido");
                        continue;
                    }
                    String pedido = mensagem.substring(6);
                    try {
                        long fence = etapa(pedido, "R");
                        // O mutex já foi liberado: esta etapa permite até K acessos juntos.
                        if (semaforo != null) etapa(pedido, "R-K" + capacidade);
                        log(pedido, "COMMITTED", "R", fence, "");
                        saida.println("COMMITTED " + pedido);
                        if (saida.checkError()) throw new IOException("Falha ao enviar confirmação.");
                    } catch (IOException | RuntimeException erro) {
                        saida.println("ERROR " + pedido);
                        System.err.println("ERRO pedido=" + pedido + " " + erro.getMessage());
                    }
                }
            }
        }
    }

    private long acquire(String recurso) throws InterruptedException {
        if (recurso.equals("R")) return protegido ? lock.lockAndGetFence() : 0;
        semaforo.acquire();
        return 0; // Semáforo usa permissões, não fencing tokens.
    }

    private void release(String recurso) {
        if (!recurso.equals("R")) semaforo.release();
        else if (protegido) lock.unlock();
    }

    private long etapa(String pedido, String recurso) throws IOException, InterruptedException {
        log(pedido, "REQUEST", recurso, 0, "");
        long inicio = System.nanoTime();
        long fence = acquire(recurso);
        try {
            log(pedido, "ACQUIRE", recurso, fence, "espera_ns=" + (System.nanoTime() - inicio));
            Verificador.registrar("BEGIN", id, pedido, recurso);
            try {
                int duracao = 200 + random.nextInt(801);
                log(pedido, "ENTER", recurso, fence, "duracao_ms=" + duracao);
                Thread.sleep(duracao); // R e a etapa do semáforo são simulados.
            } finally {
                // END antes da liberação evita falsa sobreposição no registro.
                try { log(pedido, "EXIT", recurso, fence, ""); }
                finally { Verificador.registrar("END", id, pedido, recurso); }
            }
        } finally {
            release(recurso); // Sempre na mesma thread que adquiriu.
            log(pedido, "RELEASE", recurso, fence, "");
        }
        return fence;
    }

    private void log(String pedido, String evento, String recurso, long fence, String detalhe) throws IOException {
        // Logs da equipe, agora com recurso e relógio monotônico do próprio Sync.
        String linha = System.currentTimeMillis() + ";" + id + ";" + pedido + ";" + evento
            + ";" + fence + ";" + recurso + ";" + System.nanoTime() + ";" + detalhe + "\n";
        Files.writeString(arquivo, linha, StandardOpenOption.APPEND);
        System.out.println(evento + " sync=" + id + " request_id=" + pedido + " recurso=" + recurso
            + " fence=" + fence + " protegido=" + protegido + " " + detalhe);
    }
}
