package io.whyjvm.sample.checkout;

/** Calcula o total do pedido aplicando o desconto do cliente. */
public class OrderService {

    private final CustomerService customerService;

    public OrderService(CustomerService customerService) {
        this.customerService = customerService;
    }

    public double totalWithDiscount(String customerId, double subtotal) {
        double discount = customerService.calculateDiscount(customerId);
        return subtotal * (1 - discount);
    }
}
