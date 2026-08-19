package com.passagevpn.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordEncoderConfigTest {

  private PasswordEncoder encoder() {
    java.util.Map<String, PasswordEncoder> encoders = new java.util.HashMap<>();
    encoders.put(
        "argon2id",
        org.springframework.security.crypto.argon2.Argon2PasswordEncoder
            .defaultsForSpringSecurity_v5_8());
    encoders.put("bcrypt", new BCryptPasswordEncoder());
    return new DelegatingPasswordEncoder("argon2id", encoders);
  }

  @Test
  void newPasswordsUseArgon2id() {
    PasswordEncoder enc = encoder();
    String hash = enc.encode("secret123");
    assertTrue(hash.startsWith("{argon2id}"), "New hash should use Argon2id: " + hash);
    assertTrue(enc.matches("secret123", hash));
  }

  @Test
  void legacyBcryptHashesStillVerify() {
    PasswordEncoder enc = encoder();
    BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    String bcryptHash = "{bcrypt}" + bcrypt.encode("legacy-pass");
    assertTrue(enc.matches("legacy-pass", bcryptHash));
    assertFalse(enc.matches("wrong", bcryptHash));
  }

  @Test
  void argon2idHashRejectsWrongPassword() {
    PasswordEncoder enc = encoder();
    String hash = enc.encode("correct");
    assertFalse(enc.matches("incorrect", hash));
  }
}
