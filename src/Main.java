import com.hazelcast.config.*;
import com.hazelcast.config.cp.*;
import com.hazelcast.core.*;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
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
    // Quantos membros participam do grupo Raft do CP Subsystem. Mínimo 3; usamos 5.
    static final int CP_MEMBROS = Integer.parseInt(System.getenv().getOrDefault("HZ_CP_MEMBROS", "5"));

    // Nomes das primitivas usadas no trabalho.
    static final String LOCK_R = "R";          // FencedLock que protege o recurso R
    static final String SEMAFORO_K2 = "R-K2";  // semáforo com 2 permissões
    static final String SEMAFORO_K3 = "R-K3";  // semáforo com 3 permissões

    static Config configuracao(int porta) {
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

    static void sync(int id) throws Exception {
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

        // Parte 2: aqui sobe o servidor de socket do ClusterSync (porta 6000+id).
        new CountDownLatch(1).await();
    }

    public static void main(String[] args) throws Exception {
        java.util.logging.Logger.getLogger("com.hazelcast").setLevel(java.util.logging.Level.WARNING);
        if (args.length < 2) {
            System.out.println("Comandos: sync ID | cliente ID");
            return;
        }
        switch (args[0]) {
            case "sync" -> sync(Integer.parseInt(args[1]));
            case "cliente" -> Cliente.executar(args);
            default -> System.out.println("Comando desconhecido: " + args[0]);
        }
    }
}
