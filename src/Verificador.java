import java.io.*;
import java.net.*;
import java.util.HashSet;
import java.util.Set;

public class Verificador {
    static final String HOST = System.getenv().getOrDefault("OBS_HOST", "127.0.0.1");
    static final int PORTA = Integer.parseInt(System.getenv().getOrDefault("OBS_PORT", "7000"));
    private final Set<String> ativos = new HashSet<>();
    private int entradas, saidas, maximo, sobreposicoes, erros;

    static String enviar(String mensagem) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORTA), 5000);
            socket.setSoTimeout(5000);
            PrintWriter saida = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            saida.println(mensagem);
            String resposta = entrada.readLine();
            if (resposta == null) throw new IOException("Observador encerrou sem responder.");
            return resposta;
        }
    }

    static void registrar(String evento, int syncId, String requestId) throws IOException {
        if (!"OK".equals(enviar(evento + " " + syncId + " " + requestId)))
            throw new IOException("Observador rejeitou " + evento + " " + requestId);
    }

    // Um único laço registra os eventos em ordem, sem comparar relógios de máquinas.
    static void executar() throws IOException {
        Verificador estado = new Verificador();
        try (ServerSocket servidor = new ServerSocket(PORTA)) {
            System.out.println("OBSERVADOR_PRONTO porta=" + PORTA);
            while (true) {
                try (Socket socket = servidor.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    PrintWriter saida = new PrintWriter(socket.getOutputStream(), true);
                    saida.println(estado.receber(entrada.readLine()));
                } catch (IOException erro) {
                    System.err.println("ERRO_OBSERVADOR " + erro.getMessage());
                }
            }
        }
    }

    String receber(String mensagem) {
        if ("PING".equals(mensagem)) return "PONG";
        if ("SUMMARY".equals(mensagem)) return resumo();
        if (mensagem == null || !mensagem.matches("(BEGIN|END) ([1-9]|10) [1-9][0-9]*-[1-9][0-9]*"))
            return erro("evento_invalido");
        String[] partes = mensagem.split(" ");
        String pedido = partes[2];
        if (!pedido.startsWith(partes[1] + "-")) return erro("origem_invalida");
        if (partes[0].equals("BEGIN")) {
            if (!ativos.add(pedido)) return erro("BEGIN_duplicado");
            entradas++;
            maximo = Math.max(maximo, ativos.size());
            if (ativos.size() > 1) {
                sobreposicoes++;
                System.out.println("VIOLACAO request_id=" + pedido + " ativos=" + ativos.size());
            }
        } else {
            if (!ativos.remove(pedido)) return erro("END_sem_BEGIN");
            saidas++;
        }
        System.out.println(partes[0] + " sync=" + partes[1] + " request_id=" + pedido + " ativos=" + ativos.size());
        // Confirma inclusive sobreposições: o observador nunca espera R ficar livre.
        return "OK";
    }

    private String erro(String motivo) {
        erros++;
        System.out.println("ERRO_OBSERVADOR " + motivo);
        return "ERROR " + motivo;
    }

    private String resumo() {
        return "RESUMO entradas=" + entradas + " saidas=" + saidas + " ativos=" + ativos.size()
            + " maximo=" + maximo + " sobreposicoes=" + sobreposicoes + " erros=" + erros;
    }
}
