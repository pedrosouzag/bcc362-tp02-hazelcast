import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import java.util.concurrent.TimeUnit;

/** Teste de reentrância e devolução das permissões após a carga. */
public class PrimitivasTest {
    static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        System.out.println("PASS " + label);
    }
    static HazelcastInstance connect() {
        ClientConfig c = new ClientConfig();
        c.setClusterName(System.getenv("HZ_CLUSTER"));
        c.getNetworkConfig().addAddress("127.0.0.1:5701","127.0.0.1:5702","127.0.0.1:5703","127.0.0.1:5704","127.0.0.1:5705");
        c.getConnectionStrategyConfig().getConnectionRetryConfig().setClusterConnectTimeoutMillis(20000);
        return HazelcastClient.newHazelcastClient(c);
    }
    public static void main(String[] args) throws Exception {
        java.util.logging.Logger.getLogger("com.hazelcast").setLevel(java.util.logging.Level.WARNING);
        var cfg = Main.configuracao(5701).getCPSubsystemConfig();
        System.out.println("CONFIG cpMembers="+cfg.getCPMemberCount()+" groupSize="+cfg.getGroupSize());
        if (args.length > 0 && args[0].equals("config")) return;
        HazelcastInstance a=connect(),b=null;
        try {
            b=connect();
            check(a.getCluster().getMembers().size()==5,"five_members");
            var lockA=a.getCPSubsystem().getLock("R");
            var lockB=b.getCPSubsystem().getLock("R");
            long fence1=lockA.lockAndGetFence();
            try {
                long fence2=lockA.lockAndGetFence();
                check(fence1==fence2 && lockA.getLockCount()==2,"reentrancy_same_fence_depth_2");
                check(!lockB.tryLock(300,TimeUnit.MILLISECONDS),"other_client_blocked_at_depth_2");
                lockA.unlock();
                check(lockA.isLockedByCurrentThread() && lockA.getLockCount()==1,"first_unlock_keeps_ownership");
                check(!lockB.tryLock(300,TimeUnit.MILLISECONDS),"other_client_blocked_at_depth_1");
            } finally { while(lockA.isLockedByCurrentThread()) lockA.unlock(); }
            check(lockB.tryLock(5,TimeUnit.SECONDS),"other_client_acquires_after_release");
            lockB.unlock();
            for(int k:new int[]{2,3}) {
                var sa=a.getCPSubsystem().getSemaphore("R-K"+k);
                var sb=b.getCPSubsystem().getSemaphore("R-K"+k);
                check(sa.availablePermits()==k,"semaphore_initial_"+k);
                check(sa.tryAcquire(k,5,TimeUnit.SECONDS),"semaphore_take_capacity_"+k);
                check(!sb.tryAcquire(1,300,TimeUnit.MILLISECONDS),"semaphore_blocks_above_"+k);
                sa.release(1);
                check(sb.tryAcquire(1,5,TimeUnit.SECONDS),"semaphore_shared_permits_"+k);
                sb.release(1);sa.release(k-1);
                check(sa.availablePermits()==k,"semaphore_restored_"+k);
            }
            System.out.println("PROBE_OK infrastructure_only_not_end_to_end");
        } finally {if(b!=null)b.shutdown();a.shutdown();}
    }
}
