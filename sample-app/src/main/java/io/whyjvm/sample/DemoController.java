package io.whyjvm.sample;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints de demonstracao para exercitar o gatilho.
 *
 * <ul>
 *   <li>GET /demo/ok    -> request normal, nao dispara.</li>
 *   <li>GET /demo/error -> lanca exception, dispara o circuito de RCA.</li>
 * </ul>
 */
@RestController
public class DemoController {

    @GetMapping("/demo/ok")
    public String ok() {
        return "ok";
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
