package io.whyjvm.sample;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints de demonstracao para exercitar o gatilho.
 *
 * <ul>
 *   <li>GET /demo/ok          -> request normal, nao dispara.</li>
 *   <li>GET /demo/error       -> lanca exception, dispara o circuito de RCA (ERROR).</li>
 *   <li>GET /demo/slow?mb=N   -> aloca N MB de lixo; com mb alto fica lento e
 *       gera pausa de GC, disparando um incidente SLOW que a triagem correlaciona.
 *       Aqueca o baseline com mb=0 antes de dar o pico.</li>
 *   <li>GET /demo/db-search?slow=true -> simula uma busca no banco. O caminho
 *       normal e rapido (indice); slow=true dorme 10s (query patologica) e dispara
 *       SLOW. Aqueca com slow=false antes. Sem rastro de GC/lock/alocacao no JFR —
 *       e o caso honesto de lentidao por espera/IO, sem dimensao JFR culpada.</li>
 * </ul>
 */
@RestController
public class DemoController {

    @GetMapping("/demo/ok")
    public String ok() {
        return "ok";
    }

    @GetMapping("/demo/slow")
    public String slow(@RequestParam(name = "mb", defaultValue = "0") int mb) {
        // mb=0 e rapido (aquece o baseline). Com mb alto, a alocacao pesada de
        // lixo de vida curta pressiona o GC: o request fica lento E deixa rastro
        // de alocacao/GC no JFR — material real para a triagem apontar a dimensao.
        long checksum = mb > 0 ? churnGarbage(mb) : 0;
        return "slow done: " + mb + "MB (checksum " + checksum + ")";
    }

    @GetMapping("/demo/db-search")
    public String dbSearch(@RequestParam(name = "slow", defaultValue = "false") boolean slow) {
        return "encontrados " + searchOrders(slow) + " pedidos";
    }

    private int searchOrders(boolean slow) {
        try {
            // Simula a ida ao banco. Caminho normal e rapido (query com indice);
            // slow=true simula a query patologica (full scan, lock, etc.).
            Thread.sleep(slow ? 10_000 : 8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return slow ? 1287 : 42;
    }

    private long churnGarbage(int mb) {
        List<byte[]> live = new java.util.ArrayList<>();
        long checksum = 0;
        for (int i = 0; i < mb; i++) {
            byte[] block = new byte[1024 * 1024]; // 1 MB
            block[i % block.length] = (byte) i;   // toca para nao ser otimizado fora
            checksum += block[i % block.length];
            live.add(block);
            if (live.size() > 16) {
                live.remove(0); // a maior parte vira lixo: pressiona o GC
            }
        }
        return checksum;
    }

    @GetMapping("/demo/error")
    public String error() {
        // Simula uma alocacao quadratica boba seguida de falha, so para dar corpo
        // ao stack trace que o agente vai narrar.
        List<int[]> garbage = buildLineItems(1_000);
        if (garbage.size() > 0) {
            throw new IllegalStateException("Falha simulada ao montar o pedido em /demo/error");
        }
        return "unreachable";
    }

    private List<int[]> buildLineItems(int n) {
        List<int[]> items = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            items.add(new int[i % 64]);
        }
        return items;
    }
}
