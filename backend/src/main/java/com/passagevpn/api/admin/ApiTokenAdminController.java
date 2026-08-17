package com.passagevpn.api.admin;

import com.passagevpn.token.ApiTokenService;
import com.passagevpn.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin management of API tokens used for scripted automation (admin-only). */
@RestController
@RequestMapping("/api/admin/api-tokens")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - API tokens", description = "API tokens for automation (admin-only)")
public class ApiTokenAdminController {

  private final ApiTokenService apiTokenService;

  public ApiTokenAdminController(ApiTokenService apiTokenService) {
    this.apiTokenService = apiTokenService;
  }

  @GetMapping
  public List<ApiTokenDto> list() {
    return apiTokenService.list().stream().map(ApiTokenDto::from).collect(Collectors.toList());
  }

  @PostMapping
  public ApiTokenCreatedDto create(
      @Valid @RequestBody CreateRequest request, Authentication authentication) {
    String createdBy =
        authentication != null && authentication.getName() != null
            ? authentication.getName()
            : null;
    var created =
        apiTokenService.create(request.label(), request.role(), request.expiresAt(), createdBy);
    return new ApiTokenCreatedDto(ApiTokenDto.from(created.token()), created.rawToken());
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id) {
    apiTokenService.delete(id);
  }

  public record CreateRequest(
      @NotBlank @Size(max = 128) String label, User.Role role, Instant expiresAt) {}

  /** Creation response: the plaintext token is included exactly once here. */
  public record ApiTokenCreatedDto(ApiTokenDto token, String rawToken) {}
}
