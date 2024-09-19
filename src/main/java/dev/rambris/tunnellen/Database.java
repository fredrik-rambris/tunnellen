package dev.rambris.tunnellen;

public record Database(Kind kind, String name, String username) {
    public enum Kind {
        POSTGRESQL(5432, "postgresql", "PostgreSQL","4.2", "PostgreSQL JDBC Driver", "42.6.0", "org.postgresql.Driver", "postgresql","POSTGRES", "42.6", "\\\""),
        MYSQL(3306, "mysql", "MySQL", "4.2", "MySQL Connector/J", "mysql-connector-j-8.2.0 (Revision: 06a1f724497fd81c6a659131fda822c9e5085b6c)", "com.mysql.cj.jdbc.Driver", "mysql.8", "MYSQL", "8.2", "`");

        final int port;
        final String jdbcPrefix;
        final String product;
        final String jdbcVersion;
        final String driverName;
        final String driverVersion;
        final String driverClass;
        final String driverRef;
        final String dbms;
        final String exactDriverVersion;
        final String identifierQuoteString;

        Kind(int port, String jdbcPrefix, String product, String jdbcVersion, String driverName, String driverVersion, String driverClass, String driverRef, String dbms, String exactDriverVersion, String identifierQuoteString) {
            this.port = port;
            this.jdbcPrefix = jdbcPrefix;
            this.product = product;
            this.jdbcVersion = jdbcVersion;
            this.driverName = driverName;
            this.driverVersion = driverVersion;
            this.driverClass = driverClass;
            this.driverRef = driverRef;
            this.dbms = dbms;
            this.exactDriverVersion = exactDriverVersion;
            this.identifierQuoteString = identifierQuoteString;
        }
    }
}
