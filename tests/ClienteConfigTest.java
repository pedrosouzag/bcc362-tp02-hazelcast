import java.util.Map;
import java.util.Random;

public class ClienteConfigTest {
    static void exigir(boolean valor) {
        if (!valor) throw new AssertionError("Configuração incorreta");
    }
    static void invalido(Map<String, String> ambiente) {
        try {
            Cliente.configurar(new String[]{"cliente", "1"}, ambiente);
        } catch (IllegalArgumentException esperado) { return; }
        throw new AssertionError("Configuração inválida foi aceita: " + ambiente);
    }
    public static void main(String[] args) {
        var padrao = Cliente.configurar(new String[]{"cliente", "1"}, Map.of());
        exigir(padrao.total >= 10 && padrao.total <= 50 && padrao.esperaMin == 1000 && padrao.esperaMax == 5000);
        var ambiente = Map.of("CLIENTE_PEDIDOS", "20", "CLIENTE_ESPERA_MIN_MS", "0",
            "CLIENTE_ESPERA_MAX_MS", "100", "CLIENTE_SEMENTE", "42");
        var c = Cliente.configurar(new String[]{"cliente", "1"}, ambiente);
        exigir(c.total == 20 && c.esperaMin == 0 && c.esperaMax == 100);
        var repetido = Cliente.configurar(new String[]{"cliente", "1"}, ambiente);
        Random referencia = new Random(43);
        for (int i = 0; i < 20; i++) {
            int valor = c.rnd.nextInt(101);
            exigir(valor == repetido.rnd.nextInt(101) && valor == referencia.nextInt(101));
        }
        var sorteado = Cliente.configurar(new String[]{"cliente", "2"}, Map.of("CLIENTE_SEMENTE", "42"));
        exigir(sorteado.total == 10 + new Random(44).nextInt(41));
        var argumentos = Cliente.configurar(new String[]{"cliente", "1", "10", "0"}, ambiente);
        exigir(argumentos.total == 10 && argumentos.esperaMin == 0 && argumentos.esperaMax == 0);
        for (String pedidos : new String[]{"-1", "9", "51", "abc"}) invalido(Map.of("CLIENTE_PEDIDOS", pedidos));
        invalido(Map.of("CLIENTE_ESPERA_MIN_MS", "-1"));
        invalido(Map.of("CLIENTE_ESPERA_MIN_MS", "3000", "CLIENTE_ESPERA_MAX_MS", "2000"));
        invalido(Map.of("CLIENTE_ESPERA_MAX_MS", "5001"));
        invalido(Map.of("CLIENTE_SEMENTE", "abc"));
        System.out.println("CONFIG_OK: padrões, ambiente, prioridade, sementes e limites");
    }
}
