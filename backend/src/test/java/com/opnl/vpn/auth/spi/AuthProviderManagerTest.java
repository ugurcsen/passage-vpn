package com.opnl.vpn.auth.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.user.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthProviderManagerTest {

  private final LocalAuthProvider local =
      new LocalAuthProvider(mock(UserRepository.class), mock(PasswordEncoder.class));
  private final LdapAuthProvider ldap = new LdapAuthProvider();
  private final RadiusAuthProvider radius = new RadiusAuthProvider();
  private final SamlAuthProvider saml = new SamlAuthProvider();

  private OpnlProperties propertiesWith(String provider) {
    OpnlProperties properties = mock(OpnlProperties.class);
    OpnlProperties.Auth auth = mock(OpnlProperties.Auth.class);
    when(auth.provider()).thenReturn(provider);
    when(properties.auth()).thenReturn(auth);
    return properties;
  }

  @Test
  void resolvesLocalProviderByDefault() {
    AuthProviderManager manager =
        new AuthProviderManager(propertiesWith("local"), List.of(local, ldap, radius, saml));
    assertThat(manager.active()).isEqualTo(local);
  }

  @Test
  void resolvesConfiguredRemoteProvider() {
    AuthProviderManager manager =
        new AuthProviderManager(propertiesWith("ldap"), List.of(local, ldap, radius, saml));
    assertThat(manager.active()).isEqualTo(ldap);
  }

  @Test
  void rejectsUnknownProviderAtStartup() {
    assertThatThrownBy(
            () ->
                new AuthProviderManager(
                    propertiesWith("kerberos"), List.of(local, ldap, radius, saml)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("kerberos");
  }

  @Test
  void stubProviderRejectsCredentialsWithClearError() {
    assertThatThrownBy(() -> new LdapAuthProvider().verifyCredentials("alice", "pw"))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> assertThat(((ApiException) e).getCode()).isEqualTo("provider_not_implemented"));
  }
}
