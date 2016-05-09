package com.skogsrud.halvard.jpasskit.spike;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.ModelAndView;
import spark.Request;
import spark.resource.ClassPathResource;
import spark.template.mustache.MustacheTemplateEngine;

import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import static spark.Spark.*;

public class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);
    private static final List<String> REQUIRED_ENVIRONMENT_VARIABLE_NAMES = Arrays.asList(
        "PASS_TYPE_IDENTIFIER",
        "PRIVATE_KEY_P12_BASE64",
        "PRIVATE_KEY_PASSPHRASE",
        "TEAM_IDENTIFIER"
    );
    private static final List<String> OPTIONAL_ENVIRONMENT_VARIABLE_NAMES = Arrays.asList(
        "PORT",
        "WEB_SERVICE_URL"
    );

    private final Map<String, String> environmentVariables;
    private final ObjectMapper objectMapper;
    private final MustacheTemplateEngine templateEngine;
    private final int port;

    /**
     * Main application entry point.
     */
    public static void main(String[] args) throws Exception {
        new Main(readEnvironmentVariables()).run();
    }

    Main(Map<String, String> environmentVariables) throws Exception {
        this.environmentVariables = environmentVariables;
        objectMapper = new ObjectMapper();
        templateEngine = new MustacheTemplateEngine();
        port = setPort(this.environmentVariables);
        logExceptions();
        logRequests();
        logResponses();
        disableCaching();
        enableGzip();
        forceHttps();
        logBaseUrl(port);
    }

    private void run() throws Exception {
        get("/hello.txt", (request, response) -> {
            response.type("text/plain");
            return "Hello World";
        });

        get("/hello.json", (request, response) -> {
            response.type("application/json");
            return new HashMap<String, Object>() {{
                put("hello", "world");
            }};
        }, objectMapper::writeValueAsString);

        get("/hello.html", (request, response) -> {
            HashMap<String, String> map = new HashMap<String, String>() {{
                put("hello", "world");
            }};
            return new ModelAndView(map, "hello.mustache");
        }, templateEngine);

        get("/pass", (request, response) -> {
            byte[] passAsByteArray = new Pass().createPassAsByteArray(objectMapper, environmentVariables, port);

            response.type("application/vnd.apple.pkpass");
            try (InputStream in = new ByteArrayInputStream(passAsByteArray);
                 OutputStream out = response.raw().getOutputStream()) {
                IOUtils.copy(in, out);
            }

            return ""; // don't return null, otherwise Spark will log a message saying this route hasn't been mapped
        });

        get("/pass.html", (request, response) -> {
            HashMap<String, String> map = new HashMap<String, String>() {{
                put("passTypeIdentifier", environmentVariables.get("PASS_TYPE_IDENTIFIER"));
                put("serialNumber", "serial-01234567890");
            }};
            return new ModelAndView(map, "add_to_apple_wallet.mustache");
        }, templateEngine);

        get("/images/:imageName", (request, response) -> {
            response.type("image/svg+xml");
            response.header("content-encoding", "gzip");
            String imageName = request.params(":imageName");
            String sanitisedImageName = imageName.replaceAll("[^\\w\\.-]+", "");
            if (!imageName.equals(sanitisedImageName)) {
                LOG.info("Sanitised image filename changed from [{}] to [{}]", imageName, sanitisedImageName);
            }
            try (InputStream in = new ClassPathResource("public/images/" + sanitisedImageName).getInputStream();
                 OutputStream out = new GZIPOutputStream(response.raw().getOutputStream())) {
                IOUtils.copy(in, out);
            }
            return "";
        });

        /**
         * Registering a Device to Receive Push Notifications for a Pass:
         * https://developer.apple.com/library/ios/documentation/PassKit/Reference/PassKit_WebService/WebService.html#//apple_ref/doc/uid/TP40011988-CH0-SW2
         */
        post("/wallet/v1/devices/:deviceLibraryIdentifier/registrations/" + environmentVariables.get("PASS_TYPE_IDENTIFIER") + "/:serialNumber", (request, response) -> {
            // TODO add authentication
            String sanitisedSerialNumber = extractSerialNumber(request);
            String deviceLibraryIdentifier = request.params(":deviceLibraryIdentifier");
            Map<String, String> pushTokenMap = objectMapper.readValue(request.body(), new TypeReference<Map<String, String>>() {});
            String pushToken = pushTokenMap.get("pushToken");
            LOG.debug("Received deviceLibraryIdentifier=[{}] serialNumber=[{}] pushToken=[{}]", deviceLibraryIdentifier, sanitisedSerialNumber, pushToken);
            response.status(201); // registered
            return "";
        });

        /**
         * Getting the Serial Numbers for Passes Associated with a Device:
         * https://developer.apple.com/library/ios/documentation/PassKit/Reference/PassKit_WebService/WebService.html#//apple_ref/doc/uid/TP40011988-CH0-SW4
         */
        get("/wallet/v1/devices/:deviceLibraryIdentifier/registrations/" + environmentVariables.get("PASS_TYPE_IDENTIFIER"), (request, response) -> {
//            String passesUpdatedSince = request.queryParams("passesUpdatedSince");
//            String deviceLibraryIdentifier = request.params(":serialNumber");
            response.type("application/json");
            return new HashMap<String, Object>() {{
                put("lastUpdated", UUID.randomUUID().toString());
                put("serialNumbers", Arrays.asList("serial-01234567890"));
            }};
        }, objectMapper::writeValueAsString);

        /**
         * Getting the Latest Version of a Pass:
         * https://developer.apple.com/library/ios/documentation/PassKit/Reference/PassKit_WebService/WebService.html#//apple_ref/doc/uid/TP40011988-CH0-SW6
         */
        get("/wallet/v1/passes/" + environmentVariables.get("PASS_TYPE_IDENTIFIER") + "/:serialNumber", (request, response) -> {
            // TODO add authentication
            String serialNumber = extractSerialNumber(request);
            byte[] passAsByteArray = new Pass().createPassAsByteArray(objectMapper, environmentVariables, port);
            response.type("application/vnd.apple.pkpass");
            try (InputStream in = new ByteArrayInputStream(passAsByteArray);
                 OutputStream out = response.raw().getOutputStream()) {
                IOUtils.copy(in, out);
            }
            return ""; // don't return null, otherwise Spark will log a message saying this route hasn't been mapped
        });

        /**
         * Unregistering a Device:
         * https://developer.apple.com/library/ios/documentation/PassKit/Reference/PassKit_WebService/WebService.html#//apple_ref/doc/uid/TP40011988-CH0-SW5
         */
        delete("/wallet/v1/devices/:deviceLibraryIdentifier/registrations/" + environmentVariables.get("PASS_TYPE_IDENTIFIER") + "/:serialNumber", (request, response) -> {
            // TODO add authentication
            String deviceLibraryIdentifier = request.params(":deviceLibraryIdentifier");
            String serialNumber = extractSerialNumber(request);
            LOG.debug("Received deviceLibraryIdentifier=[{}] serialNumber=[{}]", deviceLibraryIdentifier, serialNumber);
            return "";
        });

        /**
         * Logging Errors:
         * https://developer.apple.com/library/ios/documentation/PassKit/Reference/PassKit_WebService/WebService.html#//apple_ref/doc/uid/TP40011988-CH0-SW7
         */
        post("/wallet/v1/log", (request, response) -> {
            LOG.error(request.body().replaceAll("\\n", " ").replaceAll("\\t", " "));
            return "";
        });
    }

    private String extractSerialNumber(Request request) {
        String serialNumber = request.params(":serialNumber");
        String sanitisedSerialNumber = serialNumber.replaceAll("[^\\w\\.-]+", "");
        if (!serialNumber.equals(sanitisedSerialNumber)) {
            LOG.info("Sanitised serial number changed from [{}] to [{}]", serialNumber, sanitisedSerialNumber);
        }
        return sanitisedSerialNumber;
    }

    private void logExceptions() {
        exception(Exception.class, (exception, request, response) -> {
            LOG.error("Unhandled Exception", exception);
        });
    }

    private void logRequests() {
        before((request, response) -> {
            LOG.info("Request {} {}{}", request.requestMethod(), request.queryParams().isEmpty() ? request.uri() : request.uri() + "?" + request.raw().getQueryString(), request.headers("authorization") != null ? " " + request.headers("authorization") : "");
        });
    }

    private void logResponses() {
        after((request, response) -> {
        });
    }

    /**
     * Read environment variables into a map and ensure all required values are present.
     */
    private static Map<String, String> readEnvironmentVariables() {
        Map<String, String> envVars = new HashMap<>();
        REQUIRED_ENVIRONMENT_VARIABLE_NAMES.forEach(envVarName -> envVars.put(envVarName, System.getenv(envVarName)));
        List<String> missingEnvVarNames = envVars
            .entrySet()
            .stream()
            .filter(entry -> entry.getValue() == null)
            .map(entry -> entry.getKey())
            .collect(Collectors.toList());
        if (!missingEnvVarNames.isEmpty()) {
            Collections.sort(missingEnvVarNames);
            throw new IllegalArgumentException("Missing environment variables: " + missingEnvVarNames);
        }
        OPTIONAL_ENVIRONMENT_VARIABLE_NAMES.forEach(envVarName -> {
            if (System.getenv(envVarName) != null) {
                envVars.put(envVarName, System.getenv(envVarName));
            }
        });
        return envVars;
    }

    private void logBaseUrl(int port) throws UnknownHostException {
        LOG.info("Running on http://{}:{}/", InetAddress.getLocalHost().getHostAddress(), port);
    }

    /**
     * Ensure all requests are made using HTTPS if running behind a reverse proxy that supports the X-Forwarded-Proto header.
     */
    private void forceHttps() {
        before((request, response) -> {
            if ("http".equalsIgnoreCase(request.headers("x-forwarded-proto"))) {
                HttpUrl.Builder urlBuilder = HttpUrl.parse(request.url()).newBuilder();
                urlBuilder.scheme("https");
                urlBuilder.port(443);
                urlBuilder.query(request.queryString());
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
            response.header("content-encoding", "gzip");
        });
    }

    /**
     * Use port from 'PORT' environment variable, default is 4567.
     * Specify port 0 for a randomly assigned port.
     */
    private int setPort(Map<String, String> env) {
        if (env.containsKey("PORT")) {
            try {
                int port = Integer.parseInt(env.get("PORT"));
                port(port);
                return port;
            } catch (NumberFormatException e) {
                LOG.warn("Could not parse environment variable PORT, using default port 4567", e);
            }
        }
        return 4567;
    }
}
