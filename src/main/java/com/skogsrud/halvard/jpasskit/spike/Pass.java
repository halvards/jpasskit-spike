package com.skogsrud.halvard.jpasskit.spike;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.brendamour.jpasskit.PKBarcode;
import de.brendamour.jpasskit.PKField;
import de.brendamour.jpasskit.PKPass;
import de.brendamour.jpasskit.enums.PKBarcodeFormat;
import de.brendamour.jpasskit.passes.PKEventTicket;
import de.brendamour.jpasskit.signing.PKInMemorySigningUtil;
import de.brendamour.jpasskit.signing.PKPassTemplateInMemory;
import de.brendamour.jpasskit.signing.PKSigningException;
import de.brendamour.jpasskit.signing.PKSigningInformation;
import de.brendamour.jpasskit.signing.PKSigningInformationUtil;
import org.apache.commons.codec.binary.Base64InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

class Pass {
    private static final Logger LOG = LoggerFactory.getLogger(Pass.class);
    private final ObjectMapper objectMapper;

    Pass() {
        objectMapper = new ObjectMapper(); // use different ObjectMapper instance for pass creation since jpasskit registers JsonFilters
    }

    byte[] createPassAsByteArray(Map<String, String> environmentVariables, int port) throws IOException, GeneralSecurityException, PKSigningException {
        InputStream appleWwdrcaAsStream = getClass().getClassLoader().getResourceAsStream("AppleWWDRCA.pem");
        InputStream base64EncodedPrivateKeyAndCertificatePkcs12AsStream = new ByteArrayInputStream(environmentVariables.get("PRIVATE_KEY_P12_BASE64").getBytes(StandardCharsets.UTF_8));
        Base64InputStream privateKeyAndCertificatePkcs12AsStream = new Base64InputStream(base64EncodedPrivateKeyAndCertificatePkcs12AsStream);
        String privateKeyPassphrase = environmentVariables.get("PRIVATE_KEY_PASSPHRASE");
        PKSigningInformation pkSigningInformation = new PKSigningInformationUtil()
            .loadSigningInformationFromPKCS12AndIntermediateCertificate(privateKeyAndCertificatePkcs12AsStream, privateKeyPassphrase, appleWwdrcaAsStream);

        PKPass pass = new PKPass();
        pass.setFormatVersion(1);
        pass.setPassTypeIdentifier(environmentVariables.get("PASS_TYPE_IDENTIFIER"));
        String credentials = "01234567890" + ":" + "password";
        String authenticationToken = new String(Base64.getMimeEncoder(Integer.MAX_VALUE, new byte[] {'\r', '\n'}).encode(credentials.getBytes(StandardCharsets.ISO_8859_1)), StandardCharsets.ISO_8859_1);
        pass.setAuthenticationToken(authenticationToken);
//        pass.setSerialNumber("serial-01234567890");
        pass.setSerialNumber("appointment");
        pass.setTeamIdentifier(environmentVariables.get("TEAM_IDENTIFIER"));
        if (environmentVariables.containsKey("WEB_SERVICE_URL")) {
            String url = environmentVariables.get("WEB_SERVICE_URL");
            LOG.info("Apple Wallet webServiceURL=[{}]", url);
            pass.setWebServiceURL(new URL(url));
        } else {
            String url = "http://" + InetAddress.getLocalHost().getHostAddress() + ":" + port + "/wallet";
            LOG.info("Apple Wallet webServiceURL=[{}]", url);
            pass.setWebServiceURL(new URL(url));
        }
        pass.setRelevantDate(Date.from(ZonedDateTime.now(ZoneOffset.UTC).toInstant()));
        pass.setOrganizationName("Organisation Name");
        pass.setDescription("Description text");
        pass.setLogoText("Logo text");
        pass.setForegroundColor("rgb(255, 255, 255)");
        pass.setBackgroundColor("rgb(60, 65, 76)");

        PKBarcode pdf417Barcode = new PKBarcode();
        pdf417Barcode.setFormat(PKBarcodeFormat.PKBarcodeFormatPDF417);
        pdf417Barcode.setMessageEncoding(StandardCharsets.ISO_8859_1); // recommended character set for most barcode readers
        pdf417Barcode.setMessage("01234567890");
        pdf417Barcode.setAltText("01234567890");
        pass.setBarcode(pdf417Barcode);
        pass.setBarcodes(Arrays.asList(pdf417Barcode));

        PKField eventField = new PKField("event", "EVENT", "The Beat Goes On");
        PKField locationField = new PKField("loc", "LOCATION", "Moscone West");
        PKField sampleBackField = new PKField("back", "BACK", "Field");
        PKField sampleHeaderField = new PKField("header", "HEADER", "Field");
        PKField sampleAuxiliaryField = new PKField("auxiliary", "AUXILIARY", "Field");

        PKEventTicket eventTicket = new PKEventTicket();
        eventTicket.setHeaderFields(Arrays.asList(sampleHeaderField));
        eventTicket.setPrimaryFields(Arrays.asList(eventField));
        eventTicket.setSecondaryFields(Arrays.asList(locationField));
        eventTicket.setAuxiliaryFields(Arrays.asList(sampleAuxiliaryField));
        eventTicket.setBackFields(Arrays.asList(sampleBackField));
        pass.setEventTicket(eventTicket);

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
}
