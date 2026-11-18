import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class Verificador {
    static final String HOST = System.getenv().getOrDefault("OBS_HOST", "127.0.0.1");
    static final int PORTA = Integer.parseInt(System.getenv().getOrDefault("OBS_PORT", "7000"));
    static final Path PASTA = Path.of(System.getenv().getOrDefault("LOG_DIR", "logs"));

    // Cada recurso tem sua própria contagem: mutex 1, semáforos 2 e 3.
    private static class Estado {
        final int limite;
        final Set<String> ativos = new HashSet<>(), concluidos = new HashSet<>();
        int entradas, saidas, maximo, violacoes;
        Estado(int limite) { this.limite = limite; }
    }
    private final Map<String, Estado> recursos = Map.of(
        "R", new Estado(1), "R-K2", new Estado(2), "R-K3", new Estado(3));
    private int erros;

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

    static void registrar(String evento, int sync, String pedido, String recurso) throws IOException {
        if (!"OK".equals(enviar(evento + " " + sync + " " + pedido + " " + recurso)))
            throw new IOException("Observador rejeitou " + evento + " " + pedido);
    }

    static void executar() throws IOException {
        Verificador estado = new Verificador();
        Files.createDirectories(PASTA);
        // A ordem deste arquivo é a ordem dos registros, sem comparar relógios.
        try (ServerSocket servidor = new ServerSocket(PORTA);
             BufferedWriter registro = Files.newBufferedWriter(PASTA.resolve("observador.log"))) {
            System.out.println("OBSERVADOR_PRONTO porta=" + PORTA);
            while (true) {
                try (Socket socket = servidor.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    PrintWriter saida = new PrintWriter(socket.getOutputStream(), true);
                    String mensagem = entrada.readLine();
                    String resposta = estado.receber(mensagem);
                    if (mensagem != null && !mensagem.equals("PING") && !mensagem.startsWith("SUMMARY")) {
                        registro.write(mensagem);
                        registro.newLine();
                        registro.flush(); // Confirma só depois de registrar o evento.
                    }
                    saida.println(resposta);
                } catch (IOException erro) {
                    estado.erros++;
                    System.err.println("ERRO_OBSERVADOR " + erro.getMessage());
                }
            }
        }
    }

    String receber(String mensagem) {
        if ("PING".equals(mensagem)) return "PONG";
        if ("SUMMARY".equals(mensagem)) return resumo("R");
        if (mensagem != null && mensagem.startsWith("SUMMARY ")) {
            String recurso = mensagem.substring(8);
            return recursos.containsKey(recurso) ? resumo(recurso) : erro("recurso_invalido");
        }
        if (mensagem == null || !mensagem.matches("(BEGIN|END) ([1-9]|10) [1-9][0-9]*-[1-9][0-9]*( (R|R-K2|R-K3))?"))
            return erro("evento_invalido");
        String[] partes = mensagem.split(" ");
        String pedido = partes[2], recurso = partes.length == 4 ? partes[3] : "R";
        if (!pedido.startsWith(partes[1] + "-")) return erro("origem_invalida");
        Estado estado = recursos.get(recurso);
        if (partes[0].equals("BEGIN")) {
            if (estado.concluidos.contains(pedido) || !estado.ativos.add(pedido)) return erro("BEGIN_duplicado");
            estado.entradas++;
            estado.maximo = Math.max(estado.maximo, estado.ativos.size());
            if (estado.ativos.size() > estado.limite) {
                estado.violacoes++;
                System.out.println("VIOLACAO recurso=" + recurso + " request_id=" + pedido);
            }
        } else {
            if (!estado.ativos.remove(pedido)) return erro("END_sem_BEGIN");
            estado.concluidos.add(pedido);
            estado.saidas++;
        }
        System.out.println(partes[0] + " sync=" + partes[1] + " request_id=" + pedido
            + " recurso=" + recurso + " ativos=" + estado.ativos.size());
        // Mesmo uma violação recebe OK: o observador não é quem autoriza acesso.
        return "OK";
    }

    private String erro(String motivo) {
        erros++;
        return "ERROR " + motivo;
    }

    private String resumo(String recurso) {
        Estado e = recursos.get(recurso);
        return "RESUMO entradas=" + e.entradas + " saidas=" + e.saidas + " ativos=" + e.ativos.size()
            + " maximo=" + e.maximo + " sobreposicoes=" + e.violacoes + " erros=" + erros;
    }

    // Logs locais comprovam a sequência; o registro do observador comprova ocupação.
    static int verificarLogs(int pedidos, int clientes, int capacidade) {
        try {
            exigir(pedidos >= 1 && clientes >= 1 && clientes <= 10, "Quantidade esperada inválida");
            exigir(capacidade == 0 || capacidade == 2 || capacidade == 3, "K deve ser 0, 2 ou 3");
            Set<String> etapas = new HashSet<>(Set.of("R"));
            if (capacidade != 0) etapas.add("R-K" + capacidade);
            Map<String, List<String[]>> porPedido = new HashMap<>();
            int arquivos = 0;
            try (DirectoryStream<Path> logs = Files.newDirectoryStream(PASTA, "sync-*.log")) {
                for (Path arquivo : logs) {
                    arquivos++;
                    long anterior = Long.MIN_VALUE;
                    for (String linha : Files.readAllLines(arquivo)) {
                        if (linha.isBlank()) continue;
                        String[] p = linha.split(";", -1);
                        exigir(p.length == 8, "Formato inválido em " + arquivo);
                        Long.parseLong(p[0]); // Horário físico é apenas informativo.
                        int sync = Integer.parseInt(p[1]);
                        exigir(sync >= 1 && sync <= clientes && arquivo.getFileName().toString().equals("sync-" + sync + ".log"),
                            "Sync diferente do arquivo");
                        exigir(p[2].matches(sync + "-[1-9][0-9]*"), "Pedido de outro cliente");
                        int seq = Integer.parseInt(p[2].substring(p[2].indexOf('-') + 1));
                        exigir(seq <= pedidos && etapas.contains(p[5]), "Pedido ou etapa inesperada");
                        long agora = Long.parseLong(p[6]);
                        exigir(agora >= anterior, "Ordem monotônica invertida");
                        anterior = agora; // Só compara o relógio deste processo com ele mesmo.
                        Long.parseLong(p[4]);
                        porPedido.computeIfAbsent(p[2] + "/" + p[5], chave -> new ArrayList<>()).add(p);
                    }
                }
            }
            exigir(arquivos == clientes, "Faltam logs de participantes ou há arquivos extras");
            for (int id = 1; id <= clientes; id++) {
                for (int seq = 1; seq <= pedidos; seq++) {
                    String pedido = id + "-" + seq;
                    for (String etapa : etapas) {
                        List<String[]> eventos = porPedido.get(pedido + "/" + etapa);
                        List<String> esperado = new ArrayList<>(List.of("REQUEST", "ACQUIRE", "ENTER", "EXIT", "RELEASE"));
                        if (etapa.equals("R")) esperado.add("COMMITTED");
                        exigir(eventos != null && eventos.stream().map(p -> p[3]).toList().equals(esperado),
                            "Eventos ausentes, duplicados ou fora de ordem: " + pedido + "/" + etapa);
                        String fence = eventos.get(1)[4];
                        exigir(Long.parseLong(eventos.get(0)[4]) == 0, "REQUEST com fence inválido");
                        exigir(etapa.equals("R") ? Long.parseLong(fence) > 0 : fence.equals("0"), "Fence inválido");
                        for (int n = 1; n < eventos.size(); n++)
                            exigir(eventos.get(n)[4].equals(fence), "Fence mudou dentro do acesso");
                    }
                    if (capacidade != 0) {
                        List<String[]> mutex = porPedido.get(pedido + "/R");
                        List<String[]> sem = porPedido.get(pedido + "/R-K" + capacidade);
                        exigir(Long.parseLong(mutex.get(4)[6]) <= Long.parseLong(sem.get(0)[6])
                            && Long.parseLong(sem.get(4)[6]) <= Long.parseLong(mutex.get(5)[6]),
                            "Etapa do semáforo sobreposta ao mutex ou confirmação antecipada");
                    }
                }
            }
            Verificador observador = new Verificador();
            for (String linha : Files.readAllLines(PASTA.resolve("observador.log"))) {
                exigir(linha.startsWith("BEGIN ") || linha.startsWith("END "), "Registro do observador inválido");
                exigir(observador.receber(linha).equals("OK"), "Evento inconsistente no observador");
            }
            for (String recurso : observador.recursos.keySet()) {
                Estado estado = observador.recursos.get(recurso);
                int total = etapas.contains(recurso) ? pedidos * clientes : 0;
                exigir(estado.entradas == total && estado.saidas == total && estado.ativos.isEmpty()
                    && estado.violacoes == 0, "Contagem ou limite inválido: " + recurso);
                if (etapas.contains(recurso)) {
                    for (int id = 1; id <= clientes; id++)
                        for (int seq = 1; seq <= pedidos; seq++)
                            exigir(estado.concluidos.contains(id + "-" + seq), "Pedido ausente no observador");
                    if (!recurso.equals("R")) exigir(estado.maximo > 1, "Semáforo sem concorrência demonstrada");
                }
            }
            System.out.println("LOGS_OK pedidos=" + (pedidos * clientes) + " K=" + capacidade);
            return 0;
        } catch (Exception erro) {
            System.err.println("LOGS_INVALIDOS: " + erro.getMessage());
            return 1;
        }
    }

    private static void exigir(boolean condicao, String motivo) {
        if (!condicao) throw new IllegalArgumentException(motivo);
    }
}
