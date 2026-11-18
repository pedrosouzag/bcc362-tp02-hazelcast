import java.io.*;
import java.net.Socket;
import java.util.Map;
import java.util.Random;

public class Cliente {

    // guarda as opcoes do cliente (fica separado do executar para dar para testar sem abrir socket)
    static class Configuracao {
        int id;                    // qual cliente sou eu (1 a 5)
        int total;                 // quantos pedidos vou fazer
        int esperaMin, esperaMax;  // descanso entre os pedidos, em ms
        int semente;               // semente do sorteio
        boolean sementeSorteada;   // true quando ninguem passou a semente
        long inicioMs;             // largada conjunta: hora em que todos comecam (0 = comeca na hora)
        Random rnd;
    }

    // le um numero do ambiente; se a variavel nao existir, usa o valor padrao
    static int numero(Map<String, String> ambiente, String nome, int padrao) {
        String valor = ambiente.get(nome);
        if (valor == null) {
            return padrao;
        }
        return Integer.parseInt(valor);
    }

    static Configuracao configurar(String[] args, Map<String, String> ambiente) {
        Configuracao c = new Configuracao();
        c.id = Main.validarId(args[1]);

        // semente: -1 = sorteia uma e imprime, para a rodada poder ser repetida depois
        c.semente = numero(ambiente, "CLIENTE_SEMENTE", -1);
        c.sementeSorteada = (c.semente == -1);
        if (c.sementeSorteada) {
            c.semente = new Random().nextInt(Integer.MAX_VALUE);
        }
        c.rnd = new Random((long) c.semente + c.id);   // soma o id para cada cliente sortear diferente

        // largada conjunta (usada na alta contencao)
        String inicio = ambiente.get("CLIENTE_INICIO_MS");
        if (inicio != null) {
            c.inicioMs = Long.parseLong(inicio);
        }

        // pedidos: o argumento (cliente ID 10) vale mais que a variavel de ambiente
        int pedidos;
        if (args.length >= 3) {
            pedidos = Integer.parseInt(args[2]);
        } else {
            pedidos = numero(ambiente, "CLIENTE_PEDIDOS", 0);
        }
        if (pedidos != 0 && (pedidos < 10 || pedidos > 50)) {
            throw new IllegalArgumentException("Pedidos: 0 para sorteio ou 10 a 50.");
        }
        c.total = pedidos;
        if (pedidos == 0) {
            c.total = 10 + c.rnd.nextInt(41);   // sorteio de 10 a 50
        }

        // descanso entre os pedidos: o argumento (cliente ID 10 0) vale mais que o ambiente
        if (args.length >= 4) {
            c.esperaMin = Integer.parseInt(args[3]);
            c.esperaMax = c.esperaMin;
        } else {
            c.esperaMin = numero(ambiente, "CLIENTE_ESPERA_MIN_MS", 1000);
            c.esperaMax = numero(ambiente, "CLIENTE_ESPERA_MAX_MS", 5000);
        }
        // zero permite alta contencao; valores menores permitem carga desigual
        if (c.esperaMin < 0 || c.esperaMax < c.esperaMin || c.esperaMax > 5000) {
            throw new IllegalArgumentException("Espera deve respeitar 0 <= minimo <= maximo <= 5000 ms.");
        }
        return c;
    }

    static void executar(String[] args) throws Exception {
        Configuracao c = configurar(args, System.getenv());

        String aviso = "";
        if (c.sementeSorteada) {
            aviso = " (sorteada)";
        }
        System.out.println("CLIENTE id=" + c.id + " pedidos=" + c.total + " espera=" + c.esperaMin
            + ".." + c.esperaMax + "ms semente=" + c.semente + aviso);

        // largada conjunta: espera ate a hora combinada
        long falta = c.inicioMs - System.currentTimeMillis();
        if (falta > 0) {
            Thread.sleep(falta);
        }

        for (int seq = 1; seq <= c.total; seq++) {
            String pedido = c.id + "-" + seq;          // nome unico do pedido, ex.: "3-17"
            long inicio = System.nanoTime();           // relogio para medir o tempo de resposta

            // liga no meu cluster sync: saida = falar, entrada = ouvir
            try (Socket socket = new Socket("127.0.0.1", 6000 + c.id);
                 PrintWriter saida = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                saida.println("WRITE " + pedido);      // pede para escrever em r

                // espera aqui ate o cluster sync pegar o lock, escrever e responder
                String resposta = entrada.readLine();
                if (!("COMMITTED " + pedido).equals(resposta)) {
                    throw new IOException("Pedido " + pedido + " sem confirmacao valida: " + resposta);
                }

                // resposta_ms = do envio do WRITE ate o COMMITTED (espera pelo lock + regiao critica)
                System.out.println("COMMITTED cliente=" + c.id + " request_id=" + pedido
                    + " resposta_ms=" + (System.nanoTime() - inicio) / 1_000_000);
            }

            // descansa antes do proximo pedido (nao precisa descansar depois do ultimo)
            if (seq < c.total) {
                Thread.sleep(c.esperaMin + c.rnd.nextInt(c.esperaMax - c.esperaMin + 1));
            }
        }
        System.out.println("CLIENTE_CONCLUIDO id=" + c.id + " pedidos=" + c.total);
    }
}
