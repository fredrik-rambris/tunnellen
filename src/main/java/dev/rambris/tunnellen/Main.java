package dev.rambris.tunnellen;


import ch.qos.logback.classic.Logger;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.LoggerFactory;

import java.io.File;

public class Main {
    private static final Logger log = (Logger) LoggerFactory.getLogger(Main.class);
    private static Configuration config;
    private static KeepAlive keepAlive;
    private static Web web;

    private static int DEFAULT_PORT = 3000;
    private static File CONFIG_FILE = new File("forwards.yaml");

    public static void main(String[] args) throws Exception {
        commandLine(args);
        config = ConfigurationRepository.loadConfig(CONFIG_FILE, DEFAULT_PORT);
        keepAlive = new KeepAlive(config.keepAliveInterval());

        web = new Web(config);
        web.start();

        config.portForwards().forEach(tun -> {
            if (tun.isStartOnStartup()) {
                tun.start();
                keepAlive.addTunnel(tun);
            }
        });

        log.info("Listening on http://127.0.0.1:{}/. Ctrl-C to stop.", config.port());

        keepAlive.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down");
            keepAlive.stop();
            log.info("Stopping server");
            web.stop(0);
            log.info("Stopping tunnels");
            config.portForwards().forEach(Tunnel::stop);
        }));

    }

    private static void commandLine(String[] args) throws ParseException {
        var parser = new DefaultParser();
        var options = new Options();

        var portOption = Option.builder()
                .option("p")
                .longOpt("port")
                .hasArg(true)
                .type(Integer.class)
                .build();

        var configFileOption = Option.builder()
                .option("c")
                .longOpt("config")
                .hasArg(true)
                .type(File.class)
                .build();

        options
                .addOption(portOption)
                .addOption(configFileOption);

        var commandLine = parser.parse(options, args);

        DEFAULT_PORT = commandLine.getParsedOptionValue(portOption, DEFAULT_PORT);
        CONFIG_FILE = commandLine.getParsedOptionValue(configFileOption, CONFIG_FILE);
    }


    static void stopTunnel(String id) {
        config.portForwards().stream().filter(t -> t.getId().equals(id)).forEach(tunnel -> {
            keepAlive.removeTunnel(tunnel);
            tunnel.stop();
        });
    }

    static void startTunnel(String id) {
        config.portForwards().stream().filter(t -> t.getId().equals(id) && !t.isStarted()).forEach(tunnel -> {
            tunnel.start();
            keepAlive.addTunnel(tunnel);
        });
    }

    static void addTunnel(Tunnel tun) {
        config.portForwards().add(tun);

        if (tun.isStartOnStartup()) {
            tun.start();
            keepAlive.addTunnel(tun);
        }
    }

}