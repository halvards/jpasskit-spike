package com.skogsrud.halvard.jpasskit.spike;

import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;

import static spark.Spark.*;

public class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        new Main().run();
    }

    private void run() throws Exception {
        setPort();
        disableCaching();
        enableGzip();
        forceHttps();

        get("/hello", (request, response) -> {
            response.type("text/plain");
            return "Hello World";
        });
    }

    /**
     * Ensure all requests are made using HTTPS if running behind a reverse proxy that supports the X-Forwarded-Proto header.
     */
    private void forceHttps() {
        before((request, response) -> {
            if ("http".equalsIgnoreCase(request.headers("x-forwarded-proto"))) {
                HttpUrl.Builder urlBuilder = new HttpUrl.Builder();
                urlBuilder.scheme("https");
                // TBC
                String httpsUrl = urlBuilder.toString();
                response.redirect(httpsUrl, HttpServletResponse.SC_MOVED_PERMANENTLY);
            }
        });
    }

    /**
     * Disables caching across HTTP 1.0 and 1.1 client and proxy caches
     */
    private void disableCaching() {
        before((request, response) -> {
            response.header("cache-control", "no-cache, no-store, must-revalidate"); // HTTP 1.1
            response.header("pragma", "no-cache"); // HTTP 1.0
            response.header("expires", "0"); // HTTP 1.0 proxies
        });
    }

    /**
     * Enable GZIP compression of responses
     */
    private void enableGzip() {
        after((request, response) -> {
            response.header("Content-Encoding", "gzip");
        });
    }

    /**
     * Use port from 'PORT' environment variable, default is 4567.
     * Specify port 0 for a randomly assigned port.
     */
    private int setPort() {
        if (System.getenv("PORT") != null) {
            try {
                port(Integer.parseInt(System.getenv("PORT")));
            } catch (NumberFormatException e) {
                LOG.warn("Could not parse environment variable PORT, using default port 4567", e);
            }
        }
    }
}
