/*
 *  Copyright The WildFly Authors
 *  SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.grpc.ssl;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Generates all TLS certificates and PKCS12 keystores needed by the examples and integration tests.
 * Output is written to the directory passed as the first argument.
 * <p>
 * Generated files:
 * <ul>
 * <li>{@code ca.pem} — CA certificate (for grpcurl -cacert)</li>
 * <li>{@code server.keystore.p12} — server cert + key (password: {@code secret})</li>
 * <li>{@code server.truststore.p12} — CA cert for client auth (password: {@code secret})</li>
 * <li>{@code client.keystore.p12} — client cert + key for mTLS (password: {@code secret})</li>
 * <li>{@code client.truststore.p12} — CA cert for server verification (password: {@code secret})</li>
 * <li>{@code client.keystore.pem} — client cert chain PEM (for grpcurl -cert)</li>
 * <li>{@code client.key.pem} — client private key PEM (for grpcurl -key)</li>
 * </ul>
 */
public class GenerateCerts {

    private static final char[] PASSWORD = "secret".toCharArray();
    private static final String SIGNATURE_ALGORITHM = "SHA256WithRSA";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: GenerateCerts <output-dir>");
            System.exit(1);
        }
        final Path outDir = Paths.get(args[0]);
        Files.createDirectories(outDir);

        java.security.Security.addProvider(new BouncyCastleProvider());

        System.out.println("Generating TLS certificates in " + outDir.toAbsolutePath());

        // --- CA ---
        final KeyPair caKey = generateKeyPair();
        final X509Certificate caCert = selfSignedCert(caKey,
                new X500Name("CN=grpc-test-ca,O=WildFly,C=US"));

        writePem(outDir.resolve("ca.pem"), caCert);

        // --- Server ---
        final KeyPair serverKey = generateKeyPair();
        final X509Certificate serverCert = signedCert(serverKey,
                new X500Name("CN=grpc-test-server,O=WildFly,C=US"),
                caKey.getPrivate(), caCert,
                new GeneralNames(new GeneralName[] {
                        new GeneralName(GeneralName.dNSName, "localhost"),
                        new GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                        new GeneralName(GeneralName.iPAddress, "::1")
                }));

        writeP12(outDir.resolve("server.keystore.p12"),
                "server", serverKey.getPrivate(), new X509Certificate[] { serverCert, caCert });
        writeP12TrustStore(outDir.resolve("server.truststore.p12"), "ca", caCert);

        // --- Client ---
        final KeyPair clientKey = generateKeyPair();
        final X509Certificate clientCert = signedCert(clientKey,
                new X500Name("CN=grpc-test-client,O=WildFly,C=US"),
                caKey.getPrivate(), caCert, null);

        writeP12(outDir.resolve("client.keystore.p12"),
                "client", clientKey.getPrivate(), new X509Certificate[] { clientCert, caCert });
        writeP12TrustStore(outDir.resolve("client.truststore.p12"), "ca", caCert);
        writePem(outDir.resolve("client.keystore.pem"), clientCert);
        writePem(outDir.resolve("client.key.pem"), clientKey.getPrivate());

        System.out.println("Done.");
    }

    private static KeyPair generateKeyPair() throws Exception {
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048, new SecureRandom());
        return kpg.generateKeyPair();
    }

    private static X509Certificate selfSignedCert(KeyPair keyPair, X500Name subject) throws Exception {
        final Instant now = Instant.now();
        final X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(new SecureRandom().nextLong() & Long.MAX_VALUE),
                Date.from(now),
                Date.from(now.plus(30, ChronoUnit.DAYS)),
                subject,
                keyPair.getPublic());

        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

        final ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .setProvider("BC").build(keyPair.getPrivate());
        final X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
    }

    private static X509Certificate signedCert(KeyPair subjectKey, X500Name subject,
            PrivateKey caKey, X509Certificate caCert, GeneralNames san) throws Exception {
        final Instant now = Instant.now();
        final X500Name issuer = new JcaX509CertificateHolder(caCert).getSubject();
        final X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer,
                BigInteger.valueOf(new SecureRandom().nextLong() & Long.MAX_VALUE),
                Date.from(now),
                Date.from(now.plus(3650, ChronoUnit.DAYS)),
                subject,
                subjectKey.getPublic());

        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
        if (san != null) {
            builder.addExtension(Extension.subjectAlternativeName, false, san);
        }

        final ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .setProvider("BC").build(caKey);
        final X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
    }

    private static void writeP12(Path path, String alias, PrivateKey key,
            X509Certificate[] chain) throws Exception {
        final KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, PASSWORD);
        ks.setKeyEntry(alias, key, PASSWORD, chain);
        try (FileOutputStream fos = new FileOutputStream(path.toFile())) {
            ks.store(fos, PASSWORD);
        }
        System.out.println("  " + path.getFileName());
    }

    private static void writeP12TrustStore(Path path, String alias,
            X509Certificate cert) throws Exception {
        final KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, PASSWORD);
        ks.setCertificateEntry(alias, cert);
        try (FileOutputStream fos = new FileOutputStream(path.toFile())) {
            ks.store(fos, PASSWORD);
        }
        System.out.println("  " + path.getFileName());
    }

    private static void writePem(Path path, Object obj) throws IOException {
        try (JcaPEMWriter writer = new JcaPEMWriter(
                new OutputStreamWriter(new FileOutputStream(path.toFile())))) {
            writer.writeObject(obj);
        }
        System.out.println("  " + path.getFileName());
    }
}
