package dev.rambris.tunnellen;

/**
 * Throwaway manual smoke-test harness for {@link ClusterSocksTunnel}, per
 * BIG-REFACTOR.md 1.2's guidance ("write a small manual smoke-test class or
 * a {@code main()} harness that starts/stops against {@code bhg-dev} and
 * prints status"). NOT wired into the app; run standalone and delete after
 * use, or keep as a documented manual-only entry point.
 *
 * <p>Usage: {@code mvn -q exec:java -Dexec.mainClass=dev.rambris.tunnellen.ClusterSocksTunnelHarness}
 * (or run directly from an IDE) against a kubeconfig with a {@code bhg-dev}
 * context.
 */
public final class ClusterSocksTunnelHarness {
    private ClusterSocksTunnelHarness() {
    }

    public static void main(String[] args) throws InterruptedException {
        var tunnel = new ClusterSocksTunnel("bhg-dev", 19002);
        System.out.println("Starting cluster SOCKS tunnel for bhg-dev on local port 19002...");
        tunnel.start();
        for (int i = 0; i < 15; i++) {
            Thread.sleep(1000);
            boolean running = tunnel.isRunning();
            boolean alive = tunnel.isAlive();
            System.out.printf("t+%ds running=%s alive=%s%n", i + 1, running, alive);
            if (alive) {
                break;
            }
        }
        System.out.println("Final isRunning=" + tunnel.isRunning() + " isAlive=" + tunnel.isAlive());
        System.out.println("Stopping...");
        tunnel.stop();
        Thread.sleep(2000);
        System.out.println("After stop isRunning=" + tunnel.isRunning() + " isAlive=" + tunnel.isAlive());
    }
}
