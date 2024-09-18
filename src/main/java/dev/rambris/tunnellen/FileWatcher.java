package dev.rambris.tunnellen;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;

class FileWatcher {
    private static final Logger log = LoggerFactory.getLogger(FileWatcher.class);

    private Thread thread;
    private WatchService watchService;

    public interface Callback {
        void run() throws Exception;
    }

    /**
     * Starts watching a file and the given path and calls the callback when it is changed.
     * A shutdown hook is registered to stop watching. To control this yourself, create an
     * instance and use the start/stop methods.
     */
    public static void onFileChange(Path file, Callback callback) throws IOException {
        FileWatcher fileWatcher = new FileWatcher();
        fileWatcher.start(file, callback);
        Runtime.getRuntime().addShutdownHook(new Thread(fileWatcher::stop));
    }

    public void start(Path file, Callback callback) throws IOException {
        watchService = FileSystems.getDefault().newWatchService();
        Path parent = file.getParent();
        parent.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);
        log.info("Going to watch " + file);

        thread = new Thread(() -> {
            while (true) {
                WatchKey wk = null;
                try {
                    wk = watchService.take();
                    Thread.sleep(500); // give a chance for duplicate events to pile up
                    for (WatchEvent<?> event : wk.pollEvents()) {
                        Path changed = parent.resolve((Path) event.context());
                        if (Files.exists(changed) && Files.isSameFile(changed, file)) {
                            log.info("File change event: " + changed);
                            callback.run();
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    log.info("Ending my watch");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error while reloading cert", e);
                } finally {
                    if (wk != null) {
                        wk.reset();
                    }
                }
            }
        });
        thread.start();
    }

    public void stop() {
        thread.interrupt();
        try {
            watchService.close();
        } catch (IOException e) {
            log.info("Error closing watch service", e);
        }
    }

}