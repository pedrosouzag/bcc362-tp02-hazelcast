import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.cp.lock.FencedLock;
import java.util.concurrent.TimeUnit;

/**
 * Demonstração de reentrância do FencedLock "R", passo a passo, para a apresentação.
 * Conecta ao Cluster Sync já em execução como cliente Hazelcast (não é um dos 5 clientes do cenário).
 *
 * O dono do lock é (sessão CP, thread). A mesma thread pode pegar R de novo: o contador sobe
 * e o fence não muda. Outra thread, mesmo no mesmo processo, continua bloqueada até o contador voltar a 0.
 * Rodar com o cluster parado de carga, para que nenhum Sync esteja segurando R.
 */
public class Reentrancia {
    static void passo(String texto) {
        System.out.println(texto);
    }

    // Outra thread tenta pegar R por até 300 ms: mostra se o lock está bloqueado para ela.
    static boolean outraThreadConsegue(FencedLock lock) throws InterruptedException {
        boolean[] conseguiu = new boolean[1];
        Thread outra = new Thread(() -> {
            if (lock.tryLock(300, TimeUnit.MILLISECONDS)) {
                conseguiu[0] = true;
                lock.unlock();
            }
        });
        outra.start();
        outra.join();
        return conseguiu[0];
    }

    static void executar() throws Exception {
        ClientConfig config = new ClientConfig();
        config.setClusterName(Main.CLUSTER);
        config.getNetworkConfig().addAddress(Main.SEEDS.split(","));
        config.getConnectionStrategyConfig().getConnectionRetryConfig().setClusterConnectTimeoutMillis(20_000);
        HazelcastInstance hz = HazelcastClient.newHazelcastClient(config);
        try {
            FencedLock lock = hz.getCPSubsystem().getLock(Main.LOCK_R);
            if (lock.isLocked()) throw new IllegalStateException("R está ocupado: rode a demo sem carga no cluster.");
            passo("REENTRANCIA lock=" + Main.LOCK_R + " (FencedLock, CP Subsystem)");

            long fence1 = lock.lockAndGetFence();
            passo("1) lock()   fence=" + fence1 + " lockCount=" + lock.getLockCount()
                + " outraThreadConsegue=" + outraThreadConsegue(lock));

            long fence2 = lock.lockAndGetFence();
            boolean outraEm2 = outraThreadConsegue(lock);
            passo("2) lock()   fence=" + fence2 + " lockCount=" + lock.getLockCount()
                + " mesmoFence=" + (fence1 == fence2) + " outraThreadConsegue=" + outraEm2);

            lock.unlock();
            boolean outraEm1 = outraThreadConsegue(lock);
            passo("3) unlock() lockCount=" + lock.getLockCount() + " aindaDono=" + lock.isLockedByCurrentThread()
                + " outraThreadConsegue=" + outraEm1);

            lock.unlock();
            passo("4) unlock() lockCount=" + lock.getLockCount() + " livre=" + !lock.isLocked());

            long fence3 = lock.lockAndGetFence();
            lock.unlock();
            passo("5) nova aquisição: fence=" + fence3 + " (maior que " + fence1 + ": " + (fence3 > fence1) + ")");

            boolean ok = fence1 == fence2 && !outraEm2 && !outraEm1 && fence3 > fence1;
            passo(ok ? "REENTRANCIA_OK" : "REENTRANCIA_FALHOU");
            if (!ok) System.exit(1);
        } finally {
            hz.shutdown();
        }
    }
}
