package io.whyjvm.capture;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppFrameTest {

    private static final List<String> APP = List.of("io.whyjvm.sample");

    @Test
    void findsTopAppFrameSkippingJdkAndReflection() {
        String trace = """
                java.lang.NullPointerException: Cannot invoke "Customer.tier()" because "customer" is null
                \tat io.whyjvm.sample.checkout.CustomerService.calculateDiscount(CustomerService.java:20)
                \tat io.whyjvm.sample.checkout.OrderService.totalWithDiscount(OrderService.java:13)
                \tat jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
                \tat java.base/java.lang.reflect.Method.invoke(Method.java:568)""";

        AppFrame f = AppFrame.top(trace, APP).orElseThrow();

        assertEquals("io.whyjvm.sample.checkout.CustomerService.calculateDiscount", f.symbol());
        assertEquals("CustomerService.java", f.file());
        assertEquals(20, f.line());
    }

    @Test
    void stripsModuleAndClassloaderPrefix() {
        String trace = "\tat app//io.whyjvm.sample.Foo.bar(Foo.java:5)";
        AppFrame f = AppFrame.top(trace, APP).orElseThrow();
        assertEquals("io.whyjvm.sample.Foo.bar", f.symbol());
        assertEquals(5, f.line());
    }

    @Test
    void fallbackExcludeListSkipsInfraWhenNoBasePackages() {
        String trace = """
                java.lang.IllegalStateException: boom
                \tat java.base/java.util.Optional.orElseThrow(Optional.java:403)
                \tat org.springframework.web.Foo.handle(Foo.java:88)
                \tat com.acme.billing.Invoice.total(Invoice.java:42)""";

        AppFrame f = AppFrame.top(trace, List.of()).orElseThrow();

        assertEquals("com.acme.billing.Invoice.total", f.symbol());
        assertEquals(42, f.line());
    }

    @Test
    void skipsNativeAndUnknownSourceFrames() {
        String trace = """
                java.lang.RuntimeException
                \tat io.whyjvm.sample.Native.run(Native Method)
                \tat io.whyjvm.sample.Hidden.run(Unknown Source)
                \tat io.whyjvm.sample.Real.run(Real.java:9)""";

        AppFrame f = AppFrame.top(trace, APP).orElseThrow();
        assertEquals("io.whyjvm.sample.Real.run", f.symbol());
        assertEquals(9, f.line());
    }

    @Test
    void returnsFirstAppFrameAcrossCausedBy() {
        String trace = """
                jakarta.servlet.ServletException: wrap
                \tat org.springframework.web.Dispatcher.doDispatch(Dispatcher.java:1089)
                Caused by: java.lang.NullPointerException
                \tat io.whyjvm.sample.checkout.CustomerService.calculateDiscount(CustomerService.java:20)""";

        AppFrame f = AppFrame.top(trace, APP).orElseThrow();
        assertEquals("io.whyjvm.sample.checkout.CustomerService.calculateDiscount", f.symbol());
    }

    @Test
    void matchesLambdaAndInnerClassFrames() {
        String trace = "\tat io.whyjvm.sample.Svc$Worker.lambda$run$0(Svc.java:77)";
        AppFrame f = AppFrame.top(trace, APP).orElseThrow();
        assertEquals("io.whyjvm.sample.Svc$Worker.lambda$run$0", f.symbol());
        assertEquals(77, f.line());
    }

    @Test
    void emptyWhenOnlyInfraFrames() {
        String trace = """
                java.lang.NullPointerException
                \tat java.base/java.util.Objects.requireNonNull(Objects.java:233)
                \tat jdk.internal.X.y(X.java:1)""";
        assertTrue(AppFrame.top(trace, List.of()).isEmpty());
    }

    @Test
    void emptyWhenNoFrameMatchesBasePackages() {
        String trace = "\tat com.other.app.Foo.bar(Foo.java:3)";
        assertTrue(AppFrame.top(trace, APP).isEmpty());
    }

    @Test
    void doesNotMatchSiblingPackageByPrefix() {
        // base "io.whyjvm.sample" nao deve casar "io.whyjvm.samples..."
        String trace = "\tat io.whyjvm.samples.Foo.bar(Foo.java:3)";
        assertTrue(AppFrame.top(trace, APP).isEmpty());
    }

    @Test
    void emptyOnNullOrBlankTrace() {
        assertEquals(Optional.empty(), AppFrame.top(null, APP));
        assertEquals(Optional.empty(), AppFrame.top("   ", APP));
    }
}
