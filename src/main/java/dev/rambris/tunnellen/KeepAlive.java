package dev.rambris.tunnellen;

import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class KeepAlive {
    private static final Logger log = (Logger) LoggerFactory.getLogger(KeepAlive.class);
    private List<Tunnel> tunnels = new ArrayList<>();
    private boolean running = false;
    private Timer timer;
    private Duration keepAliveInterval;

    public KeepAlive(Duration keepAliveInterval) {
        this.keepAliveInterval = keepAliveInterval;
    }


    public void addTunnel(Tunnel tunnel) {
        var newTunnels = new ArrayList<>(tunnels);
        newTunnels.add(tunnel);
        tunnels = newTunnels;
    }

    public void removeTunnel(Tunnel tunnel) {
        var newTunnels = new ArrayList<>(tunnels);
        newTunnels.remove(tunnel);
        tunnels = newTunnels;
    }

    private void checkTunnels() {
        tunnels
                .parallelStream()
                .filter(e -> running && e.getLastCheck().isBefore(LocalDateTime.now().minusMinutes(1)))
                .forEach(t -> {
                    log.debug("Checking tunnel {}:{} (started:{})", t.getContext(), t.getTarget(), t.isStarted());
                    if (t.isStarted() && !t.isAlive()) {
                        log.info("Restarting tunnel");
                        t.stop();
                        t.start();
                    }
                });
    }

    public void start() {
        if(!running) {
            timer = new Timer("Keepalive");
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    checkTunnels();
                }
            }, keepAliveInterval.toMillis(), keepAliveInterval.toMillis());

            running = true;
        }
    }

    public void stop() {
        if(running) {
            running = false;
            timer.cancel();
            timer = null;
        }
    }

    public void setKeepAliveInterval(Duration keepAliveInterval) {
        this.keepAliveInterval = keepAliveInterval;
        if(running) {
            stop();
            start();
        }
    }
}
