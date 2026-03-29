package com.example.shorturl.util;

public class Base62 {
    private static final String CHARSET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = CHARSET.length();

    public static String encode(long num) {
        StringBuilder sb = new StringBuilder();
        if (num == 0)
            return String.valueOf(CHARSET.charAt(0));
        while (num > 0) {
            sb.append(CHARSET.charAt((int) (num % BASE)));
            num /= BASE;
        }
        return sb.reverse().toString();
    }

    public static long decode(String str) {
        long num = 0;
        for (int i = 0; i < str.length(); i++) {
            num = num * BASE + CHARSET.indexOf(str.charAt(i));
        }
        return num;
    }
}
