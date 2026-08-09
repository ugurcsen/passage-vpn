package com.opnl.vpn.api.admin;

import com.opnl.vpn.pki.CertService;
import com.opnl.vpn.pki.Certificate;
import com.opnl.vpn.pki.CertificateRepository;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin certificate management: list, issue, revoke. */
@RestController
@RequestMapping("/api/admin/certs")
@PreAuthorize("hasRole('ADMIN')")
public class CertAdminController {

  private final CertService certService;
  private final CertificateRepository certificateRepository;
  private final UserRepository userRepository;

  public CertAdminController(
      CertService certService,
      CertificateRepository certificateRepository,
      UserRepository userRepository) {
    this.certService = certService;
    this.certificateRepository = certificateRepository;
    this.userRepository = userRepository;
  }

  @GetMapping
  public List<CertificateDto> list() {
    Map<String, String> usernames =
        userRepository.findAll().stream()
            .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
    return certificateRepository.findAll().stream()
        .sorted(java.util.Comparator.comparing(Certificate::getIssuedAt).reversed())
        .map(c -> CertificateDto.from(c, usernames.get(c.getUserId())))
        .toList();
  }

  @PostMapping
  public CertificateDto issue(@Valid @RequestBody IssueRequest request) {
    Certificate certificate = certService.ensureUserCert(request.userId());
    return CertificateDto.from(certificate, usernameFor(certificate.getUserId()));
  }

  @GetMapping("/{id}")
  public CertificateDto get(@PathVariable String id) {
    Certificate certificate = certService.get(id);
    return CertificateDto.from(certificate, usernameFor(certificate.getUserId()));
  }

  @PostMapping("/{id}/revoke")
  public CertificateDto revoke(@PathVariable String id) {
    Certificate certificate = certService.revoke(id);
    return CertificateDto.from(certificate, usernameFor(certificate.getUserId()));
  }

  private String usernameFor(String userId) {
    return userRepository.findById(userId).map(User::getUsername).orElse(null);
  }

  public record IssueRequest(@NotBlank String userId) {}
}
