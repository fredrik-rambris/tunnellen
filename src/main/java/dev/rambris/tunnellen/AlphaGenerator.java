package dev.rambris.tunnellen;

import java.security.SecureRandom;

public class AlphaGenerator {
    private static final String ALPHA_NUMERIC = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String generate(int length) {
        return RANDOM.ints(length, 0, ALPHA_NUMERIC.length())
                .mapToObj(ALPHA_NUMERIC::charAt)
                .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append)
                .toString();
    }
}