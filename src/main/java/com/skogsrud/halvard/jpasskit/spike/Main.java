package com.skogsrud.halvard.jpasskit.spike;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.relayrides.pushy.apns.ApnsClient;
import com.relayrides.pushy.apns.ClientNotConnectedException;
import com.relayrides.pushy.apns.PushNotificationResponse;
import com.relayrides.pushy.apns.util.ApnsPayloadBuilder;
import com.relayrides.pushy.apns.util.SimpleApnsPushNotification;
import com.relayrides.pushy.apns.util.TokenUtil;
import eu.bitwalker.useragentutils.Browser;
import eu.bitwalker.useragentutils.DeviceType;
import eu.bitwalker.useragentutils.OperatingSystem;
import eu.bitwalker.useragentutils.UserAgent;
import eu.bitwalker.useragentutils.Version;
import io.netty.util.concurrent.Future;
import okhttp3.HttpUrl;
import org.apache.commons.codec.binary.Base64InputStream;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.ModelAndView;
import spark.Request;
import spark.resource.ClassPathResource;
import spark.template.mustache.MustacheTemplateEngine;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MustacheTemplateEngine templateEngine = new MustacheTemplateEngine();
    private final int port;
    private final ConcurrentHashMap<String, DeviceRegistration> usernameToRegistrationsMap = new ConcurrentHashMap<>();

    /**
     * Main application entry point.
     */
    public static void main(String[] args) throws Exception {
        new Main(readEnvironmentVariables()).run();
    }

    Main(Map<String, String> environmentVariables) throws Exception {
        this.environmentVariables = environmentVariables;
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
        redirect.get("/", "/pass");

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
            return new ModelAndView(new HashMap<String, String>() {{
                put("hello", "world");
            }}, "hello.mustache");
        }, templateEngine);

        get("/pass", (request, response) -> {
            UserAgent userAgent = UserAgent.parseUserAgentString(request.headers("user-agent"));
            if (supportsAppleWallet(userAgent)) {
                response.redirect("/pass.html");
            } else {
                response.redirect("/barcode.html");
            }
            return ""; // don't return null, otherwise Spark will log a message saying this route hasn't been mapped
        });

        get("/barcode.html", (request, response) -> {
            return new ModelAndView(new HashMap<String, String>() {{
                put("passTypeIdentifier", environmentVariables.get("PASS_TYPE_IDENTIFIER"));
                put("serialNumber", "01234567890");
            }}, "barcode.mustache");
        }, templateEngine);

        get("/barcode.png", (request, response) -> {
            String serialNumber = validateSerialNumber(request.queryParams("id"));
            String passUrl = new URI(request.url()).resolve("/wallet/v1/passes/" + environmentVariables.get("PASS_TYPE_IDENTIFIER") + "/" + serialNumber).toASCIIString();
            LOG.info("Creating barcode for URL=[{}]", passUrl);
            response.type("image/png");
            // these encoding hints are all defaults
            Map<EncodeHintType, Object> hints = new HashMap<EncodeHintType, Object>() {{
                put(EncodeHintType.CHARACTER_SET, StandardCharsets.ISO_8859_1.toString());
                put(EncodeHintType.MARGIN, 4);
                put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
            }};
            BitMatrix bitMatrix = new MultiFormatWriter().encode(passUrl, BarcodeFormat.QR_CODE, 250, 250, hints);
            BufferedImage barcodeImage = MatrixToImageWriter.toBufferedImage(bitMatrix);
            ImageIO.write(barcodeImage, "PNG", response.raw().getOutputStream());
            return "";
        });

        get("/pass.html", (request, response) -> {
            return new ModelAndView(new HashMap<String, String>() {{
                put("passTypeIdentifier", environmentVariables.get("PASS_TYPE_IDENTIFIER"));
                put("serialNumber", "01234567890");
            }}, "add_to_apple_wallet.mustache");
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
            String serialNumber = extractAndSanitiseSerialNumber(request);
            String deviceLibraryIdentifier = request.params(":deviceLibraryIdentifier");
            String username = extractUsernameAndAuthenticate(request);
            if (username == null) {
                response.status(401);
                return "";
            }
            Map<String, String> pushTokenMap = objectMapper.readValue(request.body(), new TypeReference<Map<String, String>>() {
            });
            String pushToken = pushTokenMap.get("pushToken");
            LOG.debug("Received deviceLibraryIdentifier=[{}] serialNumber=[{}] pushToken=[{}] username=[{}]", deviceLibraryIdentifier, serialNumber, pushToken, username);
            DeviceRegistration existingRegistation = usernameToRegistrationsMap.get("username");
            int statusCode = 200; // already registered
            if (existingRegistation == null || !existingRegistation.getSerialNumber().equals(serialNumber)) {
                usernameToRegistrationsMap.put(username, new DeviceRegistration(deviceLibraryIdentifier, serialNumber, pushToken));
                statusCode = 201; // new registration
            }
            response.status(statusCode);
            LOG.info("Returning statusCode=[{}] for deviceLibraryIdentifier=[{}] serialNumber=[{}] pushToken=[{}] username=[{}]", statusCode, deviceLibraryIdentifier, serialNumber, pushToken, username);
            return "";
        });

        /**
         * Getting the Serial Numbers for Passes Associated with a Device:
         * https://developer.apple.com/library/ios/documentation/PassKit/Reference/PassKit_WebService/WebService.html#//apple_ref/doc/uid/TP40011988-CH0-SW4
         */
        get("/wallet/v1/devices/:deviceLibraryIdentifier/registrations/" + environmentVariables.get("PASS_TYPE_IDENTIFIER"), (request, response) -> {
            String passesUpdatedSince = request.queryParams("passesUpdatedSince");
            String deviceLibraryIdentifier = request.params(":deviceLibraryIdentifier");
            response.type("application/json");
//            if (passesUpdatedSince == null) {
//                LOG.info("Returning empty list of serial numbers for deviceLibraryIdentifier=[{}] because passesUpdateSince query parameter is missing", deviceLibraryIdentifier);
//                return new HashMap<String, Object>() {{
//                    put("lastUpdated", UUID.randomUUID().toString());
//                    put("serialNumbers", Collections.EMPTY_LIST);
//                }};
//            }
            LOG.info("Returning list of serial numbers for deviceLibraryIdentifier=[{}] passesUpdateSince=[{}]", deviceLibraryIdentifier, deviceLibraryIdentifier);
            return new HashMap<String, Object>() {{
                put("lastUpdated", UUID.randomUUID().toString());
                put("serialNumbers", Arrays.asList("appointment"));
            }};
        }, objectMapper::writeValueAsString);

        /**
         * Getting the Latest Version of a Pass:
         * https://developer.apple.com/library/ios/documentation/PassKit/Reference/PassKit_WebService/WebService.html#//apple_ref/doc/uid/TP40011988-CH0-SW6
         */
        get("/wallet/v1/passes/" + environmentVariables.get("PASS_TYPE_IDENTIFIER") + "/:serialNumber", (request, response) -> {
            String serialNumber = extractAndSanitiseSerialNumber(request);
            String username = "appointment".equals(serialNumber) ? extractUsernameAndAuthenticate(request) : serialNumber;
            if (username == null) {
                response.status(401);
                return "";
            }
            LOG.info("Returning pass for username=[{}]", username);
            byte[] passAsByteArray = new Pass().createPassAsByteArray(environmentVariables, port);
            response.type("application/vnd.apple.pkpass");
            response.raw().addDateHeader("last-modified", Instant.now().toEpochMilli()); // devices complain if this header is missing
            try (InputStream in = new ByteArrayInputStream(passAsByteArray);
                 OutputStream out = response.raw().getOutputStream()) {
                IOUtils.copy(in, out);
            }
            return "";
        });

        /**
         * Unregistering a Device:
         * https://developer.apple.com/library/ios/documentation/PassKit/Reference/PassKit_WebService/WebService.html#//apple_ref/doc/uid/TP40011988-CH0-SW5
         */
        delete("/wallet/v1/devices/:deviceLibraryIdentifier/registrations/" + environmentVariables.get("PASS_TYPE_IDENTIFIER") + "/:serialNumber", (request, response) -> {
            // TODO add authentication
            String deviceLibraryIdentifier = request.params(":deviceLibraryIdentifier");
            String serialNumber = extractAndSanitiseSerialNumber(request);
            String username = extractUsernameAndAuthenticate(request);
            LOG.debug("Received deviceLibraryIdentifier=[{}] serialNumber=[{}] username=[{}]", deviceLibraryIdentifier, serialNumber, username);
            if (username == null) {
                response.status(401);
                return "";
            }
            usernameToRegistrationsMap.remove(username);
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

        get("/update", (request, response) -> {
            InputStream base64EncodedPrivateKeyAndCertificatePkcs12AsStream = new ByteArrayInputStream(environmentVariables.get("PRIVATE_KEY_P12_BASE64").getBytes(StandardCharsets.UTF_8));
            Base64InputStream privateKeyAndCertificatePkcs12AsStream = new Base64InputStream(base64EncodedPrivateKeyAndCertificatePkcs12AsStream);
            String privateKeyPassphrase = environmentVariables.get("PRIVATE_KEY_PASSPHRASE");

            ApnsClient<SimpleApnsPushNotification> apnsClient = new ApnsClient<>(privateKeyAndCertificatePkcs12AsStream, privateKeyPassphrase);
//            Future<Void> connectFuture = apnsClient.connect(ApnsClient.DEVELOPMENT_APNS_HOST);
            Future<Void> connectFuture = apnsClient.connect(ApnsClient.PRODUCTION_APNS_HOST);
            connectFuture.await();

            List<String> entriesToRemove = new ArrayList<>();
            usernameToRegistrationsMap.entrySet().forEach(deviceRegistrationEntry -> {
                String username = deviceRegistrationEntry.getKey();
                String pushToken = deviceRegistrationEntry.getValue().getPushToken();
                LOG.info("Pushing update for username=[{}] pushToken=[{}] passTypeIdentifier", username, pushToken, environmentVariables.get("PASS_TYPE_IDENTIFIER"));

                ApnsPayloadBuilder payloadBuilder = new ApnsPayloadBuilder();
                payloadBuilder.setAlertBody("{}");
                String payload = payloadBuilder.buildWithDefaultMaximumLength();
                String token = TokenUtil.sanitizeTokenString(pushToken);

                SimpleApnsPushNotification pushNotification = new SimpleApnsPushNotification(token, environmentVariables.get("PASS_TYPE_IDENTIFIER"), payload);
                try {
                    PushNotificationResponse<SimpleApnsPushNotification> pushNotificationResponse = apnsClient.sendNotification(pushNotification).get();
                    if (pushNotificationResponse.isAccepted()) {
                        LOG.info("Push notitification accepted by APNs gateway for username=[{}] pushToken=[{}]", username, pushToken);
                    } else {
                        LOG.error("Push notification rejected by the APNs gateway for username=[{}] pushToken=[{}]: {}", username, pushToken, pushNotificationResponse.getRejectionReason());
                        if (pushNotificationResponse.getTokenInvalidationTimestamp() != null) {
                            LOG.error("The token is invalid as of {}, removing from map.", pushNotificationResponse.getTokenInvalidationTimestamp());
                            entriesToRemove.add(deviceRegistrationEntry.getKey());
                        }
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException("Error when sending push notifications", e);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof ClientNotConnectedException) {
                        LOG.warn("Waiting for APNs client to reconnect");
                        try {
                            apnsClient.getReconnectionFuture().await();
                        } catch (InterruptedException e1) {
                            throw new RuntimeException("Error when reconnecting APNs client", e1);
                        }
                        LOG.info("APNs client reconnected after connection failure");
                    }
                }
            });
            entriesToRemove.forEach(entryToRemove -> usernameToRegistrationsMap.remove(entryToRemove));

            apnsClient.disconnect().await();
            return "";
        });
    }

    private boolean supportsAppleWallet(UserAgent userAgent) {
        if (userAgent.getOperatingSystem().getDeviceType() == DeviceType.MOBILE
            && userAgent.getOperatingSystem().getGroup() == OperatingSystem.IOS
            && userAgent.getOperatingSystem().getId() >= OperatingSystem.iOS6_IPHONE.getId()) {
            return true;
        }
        if (userAgent.getOperatingSystem() == OperatingSystem.MAC_OS_X
            && userAgent.getBrowser().getGroup() == Browser.SAFARI
            && userAgent.getBrowserVersion().compareTo(new Version("6.2", "6", "2")) >= 0) {
            return true;
        }
        return false;
    }

    private String extractUsernameAndAuthenticate(Request request) {
        String authorizationHeader = request.headers("authorization");
        if (authorizationHeader == null || !authorizationHeader.startsWith("ApplePass ")) {
            LOG.warn("Missing authorization header [{}]", authorizationHeader);
            return null;
        }
        String authorizationToken = authorizationHeader.replace("ApplePass ", "");
        String[] colonSeparatedCredentials = new String(Base64.getMimeDecoder().decode(authorizationToken), StandardCharsets.ISO_8859_1).split(":", 2);
        if (colonSeparatedCredentials.length != 2) {
            LOG.warn("Invalid authorization token [{}]", authorizationToken);
            return null;
        }
        String username = colonSeparatedCredentials[0];
        String walletPassword = colonSeparatedCredentials[1];
        if (!"password".equals(walletPassword)) {
            LOG.warn("Invalid walletPassword [{}] for username [{}]", walletPassword, username);
            return null;
        }
        return username;
    }

    private String extractAndSanitiseSerialNumber(Request request) {
        String serialNumber = request.params(":serialNumber");
        return sanitiseSerialNumber(serialNumber);
    }

    private String sanitiseSerialNumber(String serialNumber) {
        String sanitisedSerialNumber = serialNumber.replaceAll("[^\\w\\.-]+", "");
        if (!serialNumber.equals(sanitisedSerialNumber)) {
            LOG.info("Sanitised serial number changed from [{}] to [{}]", serialNumber, sanitisedSerialNumber);
        }
        return sanitisedSerialNumber;
    }

    private String validateSerialNumber(String serialNumber) {
        if (serialNumber == null) {
            throw new IllegalArgumentException("Serial number should not be null");
        }
        if (!serialNumber.matches("[0-9]{11}")) {
            throw new IllegalArgumentException("Invalid serialNumber=[" + serialNumber + "]");
        }
        return serialNumber;
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
