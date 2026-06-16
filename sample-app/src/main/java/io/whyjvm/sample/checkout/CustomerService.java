package io.whyjvm.sample.checkout;

/** Regra de desconto por tier do cliente. */
public class CustomerService {

    private final CustomerRepository repository;

    public CustomerService(CustomerRepository repository) {
        this.repository = repository;
    }

    /**
     * BUG proposital: chama o repositorio e usa o {@link Customer} <b>sem validar
     * null/empty</b>. Quando {@code findById} nao acha o cliente, retorna null e o
     * {@code customer.tier()} lanca NullPointerException — a causa que o why-jvm
     * deve narrar (e nao confundir com GC/alocacao).
     */
    public double calculateDiscount(String customerId) {
        Customer customer = repository.findById(customerId);
        return switch (customer.tier()) { // NPE aqui quando o cliente nao existe
            case "GOLD" -> 0.20;
            case "SILVER" -> 0.10;
            default -> 0.0;
        };
    }
}
