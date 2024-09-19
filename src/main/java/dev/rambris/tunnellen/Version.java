package dev.rambris.tunnellen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;

public class Version {
    private static final Logger log =  LoggerFactory.getLogger(Version.class);
    public String version=null;

    public String getVersion() {
        if(this.version==null) {
            String version = "unknown";
            try (var in = this.getClass().getClassLoader().getResourceAsStream("META-INF/maven/dev.rambris/tunnellen/pom.properties")) {
                if (in != null) {
                    var props = new Properties();
                    props.load(in);
                    version = props.getProperty("version");
                } else {
                    log.debug("Could not find pom.properties");
                }
            } catch (IOException e) {
                log.warn(e.getMessage());
            }

            this.version = version;
        }

        return this.version;
    }
}
