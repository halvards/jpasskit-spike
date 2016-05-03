package com.skogsrud.halvard.jpasskit.spike;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.brendamour.jpasskit.PKBarcode;
import de.brendamour.jpasskit.PKField;
import de.brendamour.jpasskit.PKLocation;
import de.brendamour.jpasskit.PKPass;
import de.brendamour.jpasskit.enums.PKBarcodeFormat;
import de.brendamour.jpasskit.passes.PKEventTicket;
import de.brendamour.jpasskit.signing.PKInMemorySigningUtil;
import de.brendamour.jpasskit.signing.PKPassTemplateInMemory;
import de.brendamour.jpasskit.signing.PKSigningException;
import de.brendamour.jpasskit.signing.PKSigningInformation;
import de.brendamour.jpasskit.signing.PKSigningInformationUtil;
import okhttp3.HttpUrl;
import org.apache.commons.codec.binary.Base64InputStream;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    public static void main(String[] args) throws Exception {
        new Main(getEnvironmentVariables()).run();
    }

    Main(Map<String, String> environmentVariables) {
        this.environmentVariables = environmentVariables;
        objectMapper = new ObjectMapper();
        setPort(this.environmentVariables);
        disableCaching();
        enableGzip();
        forceHttps();
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

        get("/pass", (request, response) -> {
            byte[] passAsByteArray = createPassAsByteArray();

            response.type("application/vnd.apple.pkpass");
            try (InputStream in = new ByteArrayInputStream(passAsByteArray);
                 OutputStream out = response.raw().getOutputStream()) {
                IOUtils.copy(in, out);
            }

            return ""; // don't return null, otherwise Spark will log a message saying this route hasn't been mapped
        });
    }

    byte[] createPassAsByteArray() throws IOException, GeneralSecurityException, PKSigningException {
        InputStream appleWwdrcaAsStream = getClass().getClassLoader().getResourceAsStream("AppleWWDRCA.pem");
        InputStream base64EncodedPrivateKeyAndCertificatePkcs12AsStream = new ByteArrayInputStream(environmentVariables.get("PRIVATE_KEY_P12_BASE64").getBytes(StandardCharsets.UTF_8));
        Base64InputStream privateKeyAndCertificatePkcs12AsStream = new Base64InputStream(base64EncodedPrivateKeyAndCertificatePkcs12AsStream);
        String privateKeyPassphrase = environmentVariables.get("PRIVATE_KEY_PASSPHRASE");
        PKSigningInformation pkSigningInformation = new PKSigningInformationUtil()
            .loadSigningInformationFromPKCS12AndIntermediateCertificate(privateKeyAndCertificatePkcs12AsStream, privateKeyPassphrase, appleWwdrcaAsStream);

        PKPass pass = new PKPass();
        pass.setFormatVersion(1);
        pass.setPassTypeIdentifier(environmentVariables.get("PASS_TYPE_IDENTIFIER"));
        pass.setAuthenticationToken("vxwxd7J8AlNNFPS8k0a0FfUFtq0ewzFdc");
        pass.setSerialNumber("serial-01234567890");
        pass.setTeamIdentifier(environmentVariables.get("TEAM_IDENTIFIER"));
        if (environmentVariables.containsKey("WEB_SERVICE_URL")) {
            pass.setWebServiceURL(new URL(environmentVariables.get("WEB_SERVICE_URL")));
        }
        pass.setRelevantDate(Date.from(ZonedDateTime.now(ZoneOffset.UTC).toInstant()));
        pass.setOrganizationName("Organisation Name");
        pass.setDescription("Description text");
        pass.setLogoText("Logo text");
        pass.setForegroundColor("rgb(255, 255, 255)");
        pass.setBackgroundColor("rgb(60, 65, 76)");

        PKBarcode barcode = new PKBarcode();
        barcode.setFormat(PKBarcodeFormat.PKBarcodeFormatPDF417);
        barcode.setMessageEncoding(StandardCharsets.UTF_8);
        barcode.setMessage("01234567890");
        barcode.setAltText("01234567890");
        pass.setBarcode(barcode);
        pass.setBarcodes(Arrays.asList(barcode));

        PKEventTicket eventTicket = new PKEventTicket();
        eventTicket.setPrimaryFields(Arrays.asList(new PKField("event", "EVENT", "The Beat Goes On")));
        eventTicket.setSecondaryFields(Arrays.asList(new PKField("loc", "LOCATION", "Moscone West")));
        pass.setEventTicket(eventTicket);

        PKLocation location0 = new PKLocation();
        location0.setLatitude(-122.3748889);
        location0.setLongitude(37.6189722);
        PKLocation location1 = new PKLocation();
        location1.setLatitude(-122.03118);
        location1.setLongitude(37.33182);
        pass.setLocations(Arrays.asList(location0, location1));

        if (!pass.isValid()) {
            throw new RuntimeException("Invalid pass: " + pass.getValidationErrors());
        }

        PKPassTemplateInMemory passTemplate = new PKPassTemplateInMemory();
        for (String templateFile : new String[]{
            "background.png", "background@2x.png",
            "icon.png", "icon@2x.png",
            "logo.png", "logo@2x.png",
            "thumbnail.png", "thumbnail@2x.png"
        }) {
            passTemplate.addFile(templateFile, getClass().getClassLoader().getResourceAsStream("passtemplate/" + templateFile));
        }

        return new PKInMemorySigningUtil(objectMapper).createSignedAndZippedPkPassArchive(pass, passTemplate, pkSigningInformation);
    }

    /**
     * Read environment variables into a map and ensure all required values are present.
     */
    private static Map<String, String> getEnvironmentVariables() {
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
    private void setPort(Map<String, String> env) {
        if (env.containsKey("PORT")) {
            try {
                port(Integer.parseInt(env.get("PORT")));
            } catch (NumberFormatException e) {
                LOG.warn("Could not parse environment variable PORT, using default port 4567", e);
            }
        }
    }
}
