package dev.rambris.tunnellen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.function.Consumer;

public class AsyncInputStreamReader {
    private static final Logger log = LoggerFactory.getLogger(AsyncInputStreamReader.class);
    private final Thread thread;

    public AsyncInputStreamReader(InputStream is, Consumer<String> consumer) {
        thread = Thread.ofVirtual()
                .start(() -> {
                    try (var reader = new BufferedReader(new InputStreamReader(is))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            consumer.accept(line);
                        }
                    } catch (Exception e) {
                        log.error("Error reading input stream", e);
                    }
                });
    }

    public void stop() {
        thread.interrupt();
        try {
            thread.join(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
