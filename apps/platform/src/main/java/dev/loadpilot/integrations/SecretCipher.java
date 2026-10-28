package dev.loadpilot.integrations;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecretCipher {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    public SecretCipher(@Value("${platform.connections.master-key:}") String configured) {
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(configured); } catch (IllegalArgumentException invalid) { decoded = new byte[0]; }
        key = decoded.length == 32 ? decoded : new byte[0];
    }
    public boolean available() { return key.length == 32; }
    public String seal(String value, String context) {
        if (!available()) throw ConnectionProblem.unavailable();
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, context);
            byte[] secret = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce) + "." + Base64.getEncoder().encodeToString(secret);
        } catch (GeneralSecurityException failure) { throw ConnectionProblem.unavailable(); }
    }
    public String open(String sealed, String context) {
        if (!available()) throw ConnectionProblem.unavailable();
        try {
            String[] parts = sealed.split("\\.", -1);
            if (parts.length != 2) throw new GeneralSecurityException();
            return new String(cipher(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(parts[0]), context)
                    .doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failure) { throw ConnectionProblem.unavailable(); }
    }
    private Cipher cipher(int mode, byte[] nonce, String context) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
