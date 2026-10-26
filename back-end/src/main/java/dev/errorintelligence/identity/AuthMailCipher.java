package dev.errorintelligence.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class AuthMailCipher {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final SecretKeySpec key;

  AuthMailCipher(@Value("${app.auth.signing-key}") String encodedKey) {
    try {
      byte[] signingKey = Base64.getDecoder().decode(encodedKey);
      if (signingKey.length < 32) throw new IllegalArgumentException("Signing key is too short.");
      var hash = MessageDigest.getInstance("SHA-256");
      hash.update("error-intelligence-auth-mail-v1".getBytes(StandardCharsets.UTF_8));
      this.key = new SecretKeySpec(hash.digest(signingKey), "AES");
    } catch (Exception error) {
      throw new IllegalStateException(
          "A valid AUTH_SIGNING_KEY is required for account mail.", error);
    }
  }

  Sealed seal(UUID id, String body) {
    try {
      byte[] nonce = new byte[12];
      RANDOM.nextBytes(nonce);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(idBytes(id));
      return new Sealed(nonce, cipher.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalStateException("Could not protect account mail.", error);
    }
  }

  String open(UUID id, byte[] nonce, byte[] ciphertext) {
    try {
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(idBytes(id));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (Exception error) {
      throw new IllegalStateException("Could not open account mail.", error);
    }
  }

  private static byte[] idBytes(UUID id) {
    return ByteBuffer.allocate(16)
        .putLong(id.getMostSignificantBits())
        .putLong(id.getLeastSignificantBits())
        .array();
  }

  record Sealed(byte[] nonce, byte[] ciphertext) {}
}
