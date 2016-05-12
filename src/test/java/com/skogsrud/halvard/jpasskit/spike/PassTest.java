package com.skogsrud.halvard.jpasskit.spike;

import eu.bitwalker.useragentutils.Version;
import org.apache.commons.codec.binary.Base64InputStream;
import org.apache.commons.io.IOUtils;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

public class PassTest {
    @Test
    public void createPass() throws Exception {
        Map<String, String> environmentVariables = new HashMap<String, String>() {{
            put("PASS_TYPE_IDENTIFIER", "pass.com.apple.devpubs.example");
            put("PRIVATE_KEY_P12_BASE64", IOUtils.toString(new Base64InputStream(getClass().getClassLoader().getResourceAsStream("private/TestPassKeyAndCertificate.p12"), true), StandardCharsets.UTF_8));
            put("PRIVATE_KEY_PASSPHRASE", IOUtils.toString(getClass().getClassLoader().getResourceAsStream("private/TestPassKeyAndCertificate.passphrase"), StandardCharsets.US_ASCII).trim());
            put("TEAM_IDENTIFIER", "A93A5CM278");
            put("WEB_SERVICE_URL", "https://example.com/passes/");
        }};
        byte[] passAsByteArray = new Pass().createPassAsByteArray(environmentVariables, 4567);
        try (InputStream in = new ByteArrayInputStream(passAsByteArray);
             OutputStream out = new FileOutputStream("testpass.pkpass")) {
            IOUtils.copy(in, out);
        }
    }

    @Test
    public void test() throws Exception {
        System.out.println(new Version("6.3", "6", "3").compareTo(new Version("6.2", "6", "2")));
    }

    @Test
    public void test2() throws Exception {
        System.out.println("serial-01234567890".matches("serial-[0-9]{11}"));
    }

    @Test
    public void test3() throws Exception {
        System.out.println(ZonedDateTime.now().format(Pass.DATE_TIME_FORMATTER));
    }
}
