import com.hazelcast.config.*;
import com.hazelcast.config.cp.*;
import com.hazelcast.core.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Ponto de entrada. Cada processo do Cluster Sync é um membro Hazelcast com o CP Subsystem habilitado.
 *
 *   sync ID      -> sobe o membro ID (porta 5700+ID) do Cluster Sync
 *   cliente ID   -> sobe o cliente ID, que fala só com o sync ID (porta 6000+ID)
 */
public class Main {
    static final String CLUSTER = System.getenv().getOrDefault("HZ_CLUSTER", "tp02-sync");
    // Membros conhecidos para o join TCP. Para 10 processos, passar as 10 portas em HZ_MEMBERS.
    static final String SEEDS = System.getenv().getOrDefault("HZ_MEMBERS",
        "127.0.0.1:5701,127.0.0.1:5702,127.0.0.1:5703,127.0.0.1:5704,127.0.0.1:5705");
    // Quantos membros integram o CP Subsystem. Mínimo 3; usamos 5.
    static final int CP_MEMBROS = Integer.parseInt(System.getenv().getOrDefault("HZ_CP_MEMBROS", "5"));

    // Nomes das primitivas usadas no trabalho.
    static final String LOCK_R = "R";          // FencedLock que protege o recurso R
    static final String SEMAFORO_K2 = "R-K2";  // semáforo com 2 permissões
    static final String SEMAFORO_K3 = "R-K3";  // semáforo com 3 permissões

    static Config configuracao(int porta) {
        if (CP_MEMBROS < 3) throw new IllegalArgumentException("Use pelo menos 3 membros CP.");
        Config config = new Config();
        config.setClusterName(CLUSTER);
        config.getNetworkConfig().setPort(porta).setPortAutoIncrement(false);
        var join = config.getNetworkConfig().getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getAutoDetectionConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(true).setMembers(Arrays.asList(SEEDS.split(",")));
        String bind = System.getenv("HZ_BIND");
        if (bind != null) config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface(bind);

        // CP Subsystem explicitamente habilitado: sem isso FencedLock e ISemaphore rodam em "unsafe mode".
        CPSubsystemConfig cp = config.getCPSubsystemConfig();
        cp.setCPMemberCount(CP_MEMBROS);
        // FencedLock reentrante (padrão do Hazelcast), deixado explícito para a demonstração.
        cp.addLockConfig(new FencedLockConfig(LOCK_R).enableReentrancy());
        // Semáforos da etapa de capacidade limitada. jdkCompatible=false: permissões presas à sessão CP.
        cp.addSemaphoreConfig(new SemaphoreConfig(SEMAFORO_K2, false, 2));
        cp.addSemaphoreConfig(new SemaphoreConfig(SEMAFORO_K3, false, 3));

        // Limites para executar várias JVMs em um computador pessoal.
        config.setProperty("hazelcast.operation.thread.count", "2");
        config.setProperty("hazelcast.operation.generic.thread.count", "2");
        config.setProperty("hazelcast.io.input.thread.count", "1");
        config.setProperty("hazelcast.io.output.thread.count", "1");
        config.setProperty("hazelcast.heartbeat.interval.seconds", "1");
        config.setProperty("hazelcast.max.no.heartbeat.seconds", "10");
        config.getJetConfig().setEnabled(false);
        return config;
    }

    static void sync(int id, boolean protegido, int capacidade) throws Exception {
        int porta = 5700 + id;
        HazelcastInstance hz = Hazelcast.newHazelcastInstance(configuracao(porta));
        Runtime.getRuntime().addShutdownHook(new Thread(hz::shutdown));
        System.out.println("MEMBRO_PRONTO id=" + id + " porta=" + porta);

        // O CP só funciona depois que os CP_MEMBROS primeiros membros se encontram e formam o grupo Raft.
        var cp = hz.getCPSubsystem().getCPSubsystemManagementService();
        while (!cp.awaitUntilDiscoveryCompleted(10, TimeUnit.SECONDS))
            System.out.println("AGUARDANDO_CP membros=" + hz.getCluster().getMembers().size() + "/" + CP_MEMBROS);
        var local = hz.getCPSubsystem().getLocalCPMember();
        System.out.println("CP_PRONTO id=" + id + " membrosCP=" + cp.getCPMembers().toCompletableFuture().get().size()
            + " souCP=" + (local != null));

        // O atendimento só começa depois que o CP está pronto.
        try {
            new ClusterSync(hz, id, protegido, capacidade).executar();
        } finally {
            hz.shutdown();
        }
    }

    static int validarId(String valor) {
        int id = Integer.parseInt(valor);
        if (id < 1 || id > 10) throw new IllegalArgumentException("ID deve estar entre 1 e 10.");
        return id;
    }

    public static void main(String[] args) throws Exception {
        java.util.logging.Logger.getLogger("com.hazelcast").setLevel(java.util.logging.Level.WARNING);
        if (args.length == 0) throw new IllegalArgumentException(
            "Comandos: sync ID [sem-lock|semaforo2|semaforo3] | cliente ID [pedidos] [intervalo-ms]"
            + " | observador | verificar | verificar2 | verificar3 | verificar-logs pedidos clientes [K]");
        switch (args[0]) {
            case "sync" -> {
                if (args.length < 2 || args.length > 3)
                    throw new IllegalArgumentException("Uso: sync ID [sem-lock|semaforo2|semaforo3]");
                String modo = args.length == 3 ? args[2] : "mutex";
                if (!java.util.Set.of("mutex", "sem-lock", "semaforo2", "semaforo3").contains(modo))
                    throw new IllegalArgumentException("Modo desconhecido: " + modo);
                int capacidade = modo.startsWith("semaforo") ? Integer.parseInt(modo.substring(8)) : 0;
                sync(validarId(args[1]), !modo.equals("sem-lock"), capacidade);
            }
            case "cliente" -> {
                if (args.length < 2 || args.length > 4)
                    throw new IllegalArgumentException("Uso: cliente ID [pedidos] [intervalo-ms]");
                Cliente.executar(args);
            }
            case "verificar-logs" -> {
                if (args.length < 3 || args.length > 4)
                    throw new IllegalArgumentException("Uso: verificar-logs pedidos-por-cliente clientes [K]");
                System.exit(Verificador.verificarLogs(Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                    args.length == 4 ? Integer.parseInt(args[3]) : 0));
            }
            case "observador", "verificar", "verificar2", "verificar3" -> {
                if (args.length != 1) throw new IllegalArgumentException("Este comando não recebe argumentos.");
                if (args[0].equals("observador")) Verificador.executar();
                else System.out.println(Verificador.enviar("SUMMARY " +
                    (args[0].equals("verificar") ? "R" : "R-K" + args[0].substring(9))));
            }
            default -> throw new IllegalArgumentException("Comando desconhecido: " + args[0]);
        }
    }
}
