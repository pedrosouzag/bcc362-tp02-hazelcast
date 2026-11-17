import java.io.*;
import java.net.Socket;
import java.util.Random;

public class Cliente {

    // le um numero de uma variavel de ambiente; se ela nao existir, usa o valor padrao
    static int numero(String nome, int padrao) {
        String valor = System.getenv(nome);
        return valor == null ? padrao : Integer.parseInt(valor);
    }

    static void executar(String[] args) throws Exception {
        int clientId = Integer.parseInt(args[1]);   // qual cliente sou eu (1 a 5)
        int porta = 6000 + clientId;                // ramal do meu cluster sync

        // opcoes para os experimentos. sem nenhuma delas, o cliente faz a carga normal
        int esperaMin = numero("CLIENTE_ESPERA_MIN_MS", 1000);   // descanso minimo entre pedidos (0 = alta contencao)
        int esperaMax = numero("CLIENTE_ESPERA_MAX_MS", 5000);   // descanso maximo entre pedidos
        int pedidosFixos = numero("CLIENTE_PEDIDOS", 0);         // 0 = sorteia de 10 a 50
        int semente = numero("CLIENTE_SEMENTE", -1);             // -1 = sorteio diferente a cada execucao

        // com semente, o sorteio se repete igual (cada cliente soma o seu id para nao sortear igual aos outros)
        Random rnd = new Random();
        if (semente != -1) {
            rnd = new Random(semente + clientId);
        }

        // quantos pedidos vou fazer: o valor fixo, ou um sorteio de 10 a 50
        int total = pedidosFixos > 0 ? pedidosFixos : 10 + rnd.nextInt(41);
        System.out.println("CLIENTE id=" + clientId + " pedidos=" + total + " espera=" + esperaMin + ".." + esperaMax
            + "ms semente=" + semente);

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

            // descansa antes do proximo pedido (padrao: 1 a 5 segundos)
            Thread.sleep(esperaMin + rnd.nextInt(esperaMax - esperaMin + 1));
        }
    }
}
