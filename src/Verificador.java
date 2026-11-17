import java.nio.file.*;
import java.util.ArrayList;

/*
    Le os logs e confere se dois cluster sync ficaram na regiao critica ao mesmo tempo.
    Cada linha do log: hora;sync;pedido;evento;fence
    Usa ENTER e EXIT (inicio e fim da regiao critica) e COMMITTED (pedido concluido).
    Os outros eventos (REQUEST, ACQUIRE, RELEASE) sao ignorados aqui.
 */
public class Verificador {

    // um acesso a regiao critica: quem entrou, quando entrou e quando saiu
    static class Acesso {
        String pedido;
        long fence;
        long horaEntrou;
        long horaSaiu = -1;   // -1 quer dizer que ainda nao achamos o EXIT
    }

    // devolve 0 se esta tudo certo e 1 se achou erro
    // throws Exception: avisa o java que ler arquivo pode dar erro (ex.: pasta logs nao existe)
    static int executar() throws Exception {
        int erros = 0;   // conta quantos problemas foram achados

        // passo 1: juntar todas as linhas de todos os logs numa lista so
        ArrayList<String> linhas = new ArrayList<>();
        // abre a pasta logs e pega os arquivos sync-*.log (um por cluster sync)
        try (DirectoryStream<Path> arquivos = Files.newDirectoryStream(Path.of("logs"), "sync-*.log")) {
            for (Path arquivo : arquivos) {
                for (String linha : Files.readAllLines(arquivo)) {
                    if (!linha.isBlank()) linhas.add(linha);   // ignora linha vazia
                }
            }
        }

        // passo 2: cada ENTER vira um acesso
        ArrayList<Acesso> acessos = new ArrayList<>();
        for (String linha : linhas) {
            // corta a linha nos ";" -> [0]=hora [1]=sync [2]=pedido [3]=evento [4]=fence
            String[] partes = linha.split(";");
            if (partes[3].equals("ENTER")) {
                Acesso a = new Acesso();
                a.horaEntrou = Long.parseLong(partes[0]);   // texto -> numero
                a.pedido = partes[2];
                a.fence = Long.parseLong(partes[4]);
                acessos.add(a);
            }
        }

        // passo 3: cada EXIT completa o acesso que tem o mesmo fence
        for (String linha : linhas) {
            String[] partes = linha.split(";");
            if (partes[3].equals("EXIT")) {
                long fence = Long.parseLong(partes[4]);
                boolean achou = false;
                for (Acesso a : acessos) {
                    if (a.fence == fence) {   // o fence liga o EXIT ao ENTER certo
                        a.horaSaiu = Long.parseLong(partes[0]);
                        achou = true;
                    }
                }
                if (!achou) {
                    System.out.println("ERRO: fence " + fence + " saiu sem ter entrado");
                    erros++;
                }
            }
        }

        // passo 4: todo acesso precisa ter saido
        for (Acesso a : acessos) {
            if (a.horaSaiu == -1) {
                System.out.println("ERRO: pedido " + a.pedido + " entrou e nao saiu");
                erros++;
            }
        }

        // passo 5: todo acesso precisa ter um COMMITTED (o pedido foi concluido e respondido)
        for (Acesso a : acessos) {
            boolean concluiu = false;
            for (String linha : linhas) {
                String[] partes = linha.split(";");
                if (partes[3].equals("COMMITTED") && partes[2].equals(a.pedido)) concluiu = true;
            }
            if (!concluiu) {
                System.out.println("ERRO: pedido " + a.pedido + " nao foi concluido (sem COMMITTED)");
                erros++;
            }
        }

        // passo 6: compara os acessos dois a dois (cada par so uma vez)
        for (int i = 0; i < acessos.size(); i++) {
            for (int j = i + 1; j < acessos.size(); j++) {
                Acesso a = acessos.get(i);
                Acesso b = acessos.get(j);

                // o mesmo pedido nao pode aparecer duas vezes
                if (a.pedido.equals(b.pedido)) {
                    System.out.println("ERRO: pedido repetido " + a.pedido);
                    erros++;
                }
                // o lock nunca entrega o mesmo fence a dois acessos
                if (a.fence == b.fence) {
                    System.out.println("ERRO: fence repetido " + a.fence);
                    erros++;
                }
                // se um entrou antes do outro sair, e o outro tambem entrou antes dele sair,
                // os dois estavam la dentro ao mesmo tempo
                boolean temSaida = a.horaSaiu != -1 && b.horaSaiu != -1;   // so compara se os dois ja sairam
                if (temSaida && a.horaEntrou < b.horaSaiu && b.horaEntrou < a.horaSaiu) {
                    System.out.println("ERRO: SOBREPOSICAO entre " + a.pedido + " e " + b.pedido);
                    erros++;
                }
            }
        }

        // resultado: 0 = tudo certo, 1 = achou erro
        if (erros == 0) {
            System.out.println("OK: " + acessos.size() + " acessos, todos concluidos, nenhuma sobreposicao");
            return 0;
        }
        System.out.println("FALHOU: " + erros + " erro(s)");
        return 1;
    }
}
