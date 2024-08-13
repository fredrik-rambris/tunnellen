package dev.rambris.tunnellen;

import java.io.InputStream;
import java.util.function.Consumer;

public class AsyncInputStreamReader {
    private boolean running = true;
    private Thread thread;
    public AsyncInputStreamReader(InputStream is, Consumer<String> consumer) {
        thread = Thread.ofVirtual()
                .start(() -> {
                    var buffer = new StringBuffer();
                    try {
                        while (running) {
                            if(is.available()==0) {
                                Thread.sleep(100);
                                continue;
                            }

                            var b = is.read();

                            if(b=='\n' || b=='\r' || b==-1 && !buffer.isEmpty()) {
                                consumer.accept(buffer.toString());
                                buffer.setLength(0);
                            } else if(b==-1) {
                                break;
                            } else {
                                buffer.append((char)b);
                            }

                        }
                    } catch (Exception __) {
                        __.printStackTrace();
                    }
                });
    }

    public void stop() {
        running = false;
        try {
            thread.join(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
