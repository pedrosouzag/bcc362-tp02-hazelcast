import java.io.*;
import java.net.Socket;
import java.util.Random;

public class Cliente {

    static void executar(String[] args) throws Exception {
        int clientId = Integer.parseInt(args[1]);   // qual cliente sou eu (1 a 5)
        int porta = 6000 + clientId;                // ramal do meu cluster sync
        Random rnd = new Random();

        int total = 10 + rnd.nextInt(41);           // quantos pedidos vou fazer (10 a 50)

        for (int seq = 1; seq <= total; seq++) {
            String requestId = clientId + "-" + seq;   // nome unico do pedido, id do cliente - numero do pedido

            // liga no cluster sync: saida = falar, entrada = ouvir
            try (Socket socket = new Socket("127.0.0.1", porta);
                 PrintWriter saida = new PrintWriter(socket.getOutputStream(), true);   // converte texto em bytes e envia (falar)
                 BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {   // converte bytes recebidos em texto, linha a linha (ouvir)

                    saida.println("WRITE " + requestId);   // pede para escrever em r
                    // espera a resposta: so volta quando o cluster sync conseguir o lock (entrar na regiao critica) e terminar
                    String resposta = entrada.readLine();   // resposta: "COMMITTED " + requestId, ex.: "COMMITTED 3-17"
                    System.out.println("Recebi: " + resposta);
            }

            // descansa de 1 a 5 segundos antes do proximo pedido
            Thread.sleep(1000 + rnd.nextInt(4001));
        }
    }
}
