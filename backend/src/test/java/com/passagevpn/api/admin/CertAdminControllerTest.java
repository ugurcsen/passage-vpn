package com.passagevpn.api.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.passagevpn.common.GlobalExceptionHandler;
import com.passagevpn.pki.CertService;
import com.passagevpn.pki.Certificate;
import com.passagevpn.pki.Certificate.Status;
import com.passagevpn.pki.CertificateRepository;
import com.passagevpn.user.User;
import com.passagevpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer tests for the certificate admin API (security enforced by @PreAuthorize). */
class CertAdminControllerTest {

  private CertService certService;
  private CertificateRepository certificateRepository;
  private UserRepository userRepository;
  private MockMvc mvc;

  private Certificate cert(String id, String cn, Status status, String userId) {
    return Certificate.builder()
        .id(id)
        .commonName(cn)
        .userId(userId)
        .serial("AB")
        .status(status)
        .issuedAt(Instant.now())
        .build();
  }

  @BeforeEach
  void setUp() {
    certService = mock(CertService.class);
    certificateRepository = mock(CertificateRepository.class);
    userRepository = mock(UserRepository.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new CertAdminController(certService, certificateRepository, userRepository))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void listReturnsAllCertificates() throws Exception {
    when(userRepository.findAll())
        .thenReturn(List.of(User.builder().id("u1").username("alice").build()));
    when(certificateRepository.findAll())
        .thenReturn(List.of(cert("c1", "alice", Status.VALID, "u1")));

    mvc.perform(get("/api/admin/certs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("c1"))
        .andExpect(jsonPath("$[0].username").value("alice"));
  }

  @Test
  void listHandlesReconciledCertificatesWithoutIssueDate() throws Exception {
    when(userRepository.findAll())
        .thenReturn(List.of(User.builder().id("u1").username("alice").build()));
    Certificate reconciled = cert("c2", "legacy-user", Status.VALID, "u1");
    reconciled.setIssuedAt(null);
    when(certificateRepository.findAll()).thenReturn(List.of(reconciled));

    mvc.perform(get("/api/admin/certs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("c2"))
        .andExpect(jsonPath("$[0].issuedAt").isEmpty());
  }

  @Test
  void listWithExpiringFiltersToExpiringSoon() throws Exception {
    when(userRepository.findAll()).thenReturn(List.of());
    when(certService.expiringSoon()).thenReturn(List.of(cert("c1", "alice", Status.VALID, "u1")));

    mvc.perform(get("/api/admin/certs").param("expiring", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("c1"));

    verify(certService).expiringSoon();
  }

  @Test
  void revokeDelegatesToService() throws Exception {
    when(certService.revoke("c1")).thenReturn(cert("c1", "alice", Status.REVOKED, "u1"));
    when(userRepository.findById("u1"))
        .thenReturn(Optional.of(User.builder().id("u1").username("alice").build()));

    mvc.perform(post("/api/admin/certs/c1/revoke"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REVOKED"));

    verify(certService).revoke("c1");
  }

  @Test
  void restoreDelegatesToService() throws Exception {
    when(certService.restore("c1")).thenReturn(cert("c1", "alice", Status.VALID, "u1"));
    when(userRepository.findById("u1"))
        .thenReturn(Optional.of(User.builder().id("u1").username("alice").build()));

    mvc.perform(post("/api/admin/certs/c1/restore"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VALID"));

    verify(certService).restore("c1");
  }

  @Test
  void rotateDelegatesToServiceWithCertificateUser() throws Exception {
    when(certService.get("c1")).thenReturn(cert("c1", "alice", Status.VALID, "u1"));
    Certificate rotated = cert("c1", "alice", Status.VALID, "u1");
    when(certService.rotate("u1")).thenReturn(rotated);
    when(userRepository.findById("u1"))
        .thenReturn(Optional.of(User.builder().id("u1").username("alice").build()));

    mvc.perform(post("/api/admin/certs/c1/rotate"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VALID"));

    verify(certService).rotate("u1");
  }

  @Test
  void reconcileReturnsCounts() throws Exception {
    when(certService.reconcile()).thenReturn(new CertService.ReconcileResult(2, 3, 1));

    mvc.perform(post("/api/admin/certs/reconcile"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(2))
        .andExpect(jsonPath("$.updated").value(3))
        .andExpect(jsonPath("$.skipped").value(1));

    verify(certService).reconcile();
  }

  @Test
  void rotateRejectsUnboundCertificate() throws Exception {
    when(certService.get("c1")).thenReturn(cert("c1", "alice", Status.VALID, null));

    mvc.perform(post("/api/admin/certs/c1/rotate"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("no_user"));
  }
}
