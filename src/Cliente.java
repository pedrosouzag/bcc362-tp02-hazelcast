import java.io.*;
import java.net.Socket;
import java.util.Random;

public class Cliente {

    static void executar(String[] args) throws Exception {
        int clientId = Main.validarId(args[1]);     // cada cliente conhece apenas seu Sync
        int porta = 6000 + clientId;                // ramal do meu cluster sync
        Random rnd = new Random();

        int total = args.length >= 3 ? Integer.parseInt(args[2]) : 10 + rnd.nextInt(41);
        int intervalo = args.length >= 4 ? Integer.parseInt(args[3]) : -1;
        if (total < 10 || total > 50) throw new IllegalArgumentException("Use de 10 a 50 pedidos.");
        if (args.length >= 4 && intervalo != 0 && (intervalo < 1000 || intervalo > 5000))
            throw new IllegalArgumentException("Intervalo: 0 para contenção ou 1000 a 5000 ms.");

        for (int seq = 1; seq <= total; seq++) {
            String requestId = clientId + "-" + seq;   // nome unico do pedido, id do cliente - numero do pedido

            // liga no cluster sync: saida = falar, entrada = ouvir
            try (Socket socket = new Socket("127.0.0.1", porta);
                 PrintWriter saida = new PrintWriter(socket.getOutputStream(), true);   // converte texto em bytes e envia (falar)
                 BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {   // converte bytes recebidos em texto, linha a linha (ouvir)

                    saida.println("WRITE " + requestId);   // pede para escrever em r
                    // espera a resposta: so volta quando o cluster sync conseguir o lock (entrar na regiao critica) e terminar
                    String resposta = entrada.readLine();   // resposta: "COMMITTED " + requestId, ex.: "COMMITTED 3-17"
                    // Só esta confirmação prova que o nosso pedido terminou.
                    if (!("COMMITTED " + requestId).equals(resposta))
                        throw new IOException("Pedido " + requestId + " sem confirmação válida: " + resposta);
                    System.out.println("COMMITTED cliente=" + clientId + " request_id=" + requestId);
            }

            // descansa de 1 a 5 segundos antes do proximo pedido
            if (seq < total) Thread.sleep(intervalo < 0 ? 1000 + rnd.nextInt(4001) : intervalo);
        }
        System.out.println("CLIENTE_CONCLUIDO id=" + clientId + " pedidos=" + total);
    }
}
