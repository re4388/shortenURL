package com.example.shorturl.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public class UrlValidator {

    /**
     * Normalizes a URL to its canonical form for blacklist checking.
     * Focuses on scheme, host, and port normalization.
     */
    public static String getNormalizedHost(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "";
        }

        try {
            // Ensure URI starts with a scheme for parsing
            String urlToParse = url.trim();
            if (!urlToParse.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
                urlToParse = "http://" + urlToParse;
            }

            URI uri = new URI(urlToParse);
            String host = uri.getHost();

            if (host == null) {
                return "";
            }

            // Lowercase normalization
            return host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return "";
        }
    }

    /**
     * Validates if a string is a potentially valid URL format.
     */
    public static boolean isValidUrl(String url) {
        if (url == null || url.length() > 2048) return false;
        try {
            URI uri = new URI(url);
            return uri.getScheme() != null && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }
}
