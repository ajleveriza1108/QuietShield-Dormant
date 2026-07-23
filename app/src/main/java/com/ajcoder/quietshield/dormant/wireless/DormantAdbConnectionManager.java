package com.ajcoder.quietshield.dormant.wireless;

import android.content.Context;
import android.os.Build;
import android.sun.misc.BASE64Encoder;
import android.sun.security.provider.X509Factory;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * Stores the Wireless Debugging identity only in QuietShield Dormant's private files.
 * The identity survives normal restarts and is deleted when app data is cleared.
 */
public final class DormantAdbConnectionManager extends AbsAdbConnectionManager {
    private static DormantAdbConnectionManager instance;
    private static final String KEY_DIR = "wireless_adb";
    private static final String PRIVATE_KEY = "private.key";
    private static final String CERTIFICATE = "certificate.pem";

    @NonNull
    public static synchronized DormantAdbConnectionManager getInstance(@NonNull Context context) throws Exception {
        if (instance == null) {
            instance = new DormantAdbConnectionManager(context.getApplicationContext());
        }
        return instance;
    }

    public static boolean hasSavedIdentity(@NonNull Context context) {
        File directory = new File(context.getFilesDir(), KEY_DIR);
        return new File(directory, PRIVATE_KEY).isFile() && new File(directory, CERTIFICATE).isFile();
    }

    private final PrivateKey privateKey;
    private final Certificate certificate;

    private DormantAdbConnectionManager(@NonNull Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        File directory = new File(context.getFilesDir(), KEY_DIR);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to prepare the private wireless setup folder.");
        }

        PrivateKey existingPrivateKey = readPrivateKey(directory);
        Certificate existingCertificate = readCertificate(directory);
        if (existingPrivateKey != null && existingCertificate != null) {
            privateKey = existingPrivateKey;
            certificate = existingCertificate;
            return;
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
        KeyPair keyPair = generator.generateKeyPair();
        privateKey = keyPair.getPrivate();
        certificate = generateCertificate(keyPair.getPublic(), privateKey);
        writePrivateKey(directory, privateKey);
        writeCertificate(directory, certificate);
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return "QuietShield Dormant";
    }

    @NonNull
    private static Certificate generateCertificate(
            @NonNull PublicKey publicKey,
            @NonNull PrivateKey privateKey
    ) throws Exception {
        String subject = "CN=QuietShield Dormant";
        String algorithm = "SHA512withRSA";
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 60_000L);
        Date notAfter = new Date(now + 10L * 365L * 24L * 60L * 60L * 1000L);

        CertificateExtensions extensions = new CertificateExtensions();
        extensions.set("SubjectKeyIdentifier", new SubjectKeyIdentifierExtension(
                new KeyIdentifier(publicKey).getIdentifier()));
        extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));

        X500Name name = new X500Name(subject);
        X509CertInfo info = new X509CertInfo();
        info.set("version", new CertificateVersion(2));
        info.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithm)));
        info.set("subject", new CertificateSubjectName(name));
        info.set("key", new CertificateX509Key(publicKey));
        info.set("validity", new CertificateValidity(notBefore, notAfter));
        info.set("issuer", new CertificateIssuerName(name));
        info.set("extensions", extensions);

        X509CertImpl result = new X509CertImpl(info);
        result.sign(privateKey, algorithm);
        return result;
    }

    @Nullable
    private static Certificate readCertificate(@NonNull File directory)
            throws IOException, CertificateException {
        File file = new File(directory, CERTIFICATE);
        if (!file.isFile()) return null;
        try (InputStream input = new FileInputStream(file)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static void writeCertificate(@NonNull File directory, @NonNull Certificate certificate)
            throws CertificateEncodingException, IOException {
        File file = new File(directory, CERTIFICATE);
        BASE64Encoder encoder = new BASE64Encoder();
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(X509Factory.BEGIN_CERT.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            encoder.encode(certificate.getEncoded(), output);
            output.write('\n');
            output.write(X509Factory.END_CERT.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nullable
    private static PrivateKey readPrivateKey(@NonNull File directory)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        File file = new File(directory, PRIVATE_KEY);
        if (!file.isFile()) return null;
        byte[] encoded = new byte[(int) file.length()];
        try (InputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < encoded.length) {
                int count = input.read(encoded, offset, encoded.length - offset);
                if (count < 0) break;
                offset += count;
            }
            if (offset != encoded.length) throw new IOException("The saved wireless key is incomplete.");
        }
        KeyFactory factory = KeyFactory.getInstance("RSA");
        EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
        return factory.generatePrivate(keySpec);
    }

    private static void writePrivateKey(@NonNull File directory, @NonNull PrivateKey privateKey)
            throws IOException {
        File file = new File(directory, PRIVATE_KEY);
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(privateKey.getEncoded());
        }
    }
}
