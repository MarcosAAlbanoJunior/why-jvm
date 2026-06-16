package io.whyjvm.sample.checkout;

/**
 * "Banco" simulado: so conhece o cliente {@code c-1}. Qualquer outro id retorna
 * {@code null} (nao encontrado) — a origem do NullPointer de exemplo.
 */
public class CustomerRepository {

    public Customer findById(String id) {
        return "c-1".equals(id) ? new Customer("c-1", "GOLD") : null;
    }
}
