import java.io.*;
import java.net.Socket;
import java.util.Map;
import java.util.Random;

public class Cliente {
    // Separar a configuração permite testar os sorteios sem abrir sockets.
    static class Configuracao {
        int id, total, esperaMin, esperaMax, semente;
        Random rnd;
    }

    static int numero(Map<String, String> ambiente, String nome, int padrao) {
        String valor = ambiente.get(nome);
        return valor == null ? padrao : Integer.parseInt(valor);
    }

    static Configuracao configurar(String[] args, Map<String, String> ambiente) {
        Configuracao c = new Configuracao();
        c.id = Main.validarId(args[1]);
        c.semente = numero(ambiente, "CLIENTE_SEMENTE", -1);
        c.rnd = c.semente == -1 ? new Random() : new Random((long) c.semente + c.id);
        // Argumentos explícitos têm prioridade sobre as variáveis de ambiente.
        int pedidos = args.length >= 3 ? Integer.parseInt(args[2]) : numero(ambiente, "CLIENTE_PEDIDOS", 0);
        if (pedidos != 0 && (pedidos < 10 || pedidos > 50))
            throw new IllegalArgumentException("Pedidos: 0 para sorteio ou 10 a 50.");
        c.total = pedidos == 0 ? 10 + c.rnd.nextInt(41) : pedidos;
        if (args.length >= 4) {
            c.esperaMin = c.esperaMax = Integer.parseInt(args[3]);
        } else {
            c.esperaMin = numero(ambiente, "CLIENTE_ESPERA_MIN_MS", 1000);
            c.esperaMax = numero(ambiente, "CLIENTE_ESPERA_MAX_MS", 5000);
        }
        // Zero permite alta contenção; intervalos menores permitem carga desigual.
        if (c.esperaMin < 0 || c.esperaMax < c.esperaMin || c.esperaMax > 5000)
            throw new IllegalArgumentException("Espera deve respeitar 0 <= mínimo <= máximo <= 5000 ms.");
        return c;
    }

    static void executar(String[] args) throws Exception {
        Configuracao c = configurar(args, System.getenv());
        System.out.println("CLIENTE id=" + c.id + " pedidos=" + c.total + " espera=" + c.esperaMin
            + ".." + c.esperaMax + "ms semente=" + c.semente);
        for (int seq = 1; seq <= c.total; seq++) {
            String pedido = c.id + "-" + seq;
            // Cada cliente conhece somente o seu Sync.
            try (Socket socket = new Socket("127.0.0.1", 6000 + c.id);
                 PrintWriter saida = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                saida.println("WRITE " + pedido);
                String resposta = entrada.readLine();
                if (!("COMMITTED " + pedido).equals(resposta))
                    throw new IOException("Pedido " + pedido + " sem confirmação válida: " + resposta);
                System.out.println("COMMITTED cliente=" + c.id + " request_id=" + pedido);
            }
            // Não acrescentamos uma espera artificial depois do último pedido.
            if (seq < c.total) Thread.sleep(c.esperaMin + c.rnd.nextInt(c.esperaMax - c.esperaMin + 1));
        }
        System.out.println("CLIENTE_CONCLUIDO id=" + c.id + " pedidos=" + c.total);
    }
}
