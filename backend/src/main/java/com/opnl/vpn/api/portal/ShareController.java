package com.opnl.vpn.api.portal;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.profile.ProfileService;
import com.opnl.vpn.profile.ProfileService.OvpnFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public token-based profile download serving the raw .ovpn file. The QR-code payload and admin
 * share links point here; the token itself is the authorization, so no login is required. OpenVPN
 * Connect imports the response directly (application/x-openvpn-profile), while a plain browser
 * download triggers via Content-Disposition.
 */
@RestController
@RequestMapping("/share")
@Tag(name = "Share", description = "Public token-based .ovpn downloads (QR codes, share links)")
public class ShareController {

  private static final String OVPN_CONTENT_TYPE = "application/x-openvpn-profile";

  private final ProfileService profileService;

  public ShareController(ProfileService profileService) {
    this.profileService = profileService;
  }

  @GetMapping("/{token}")
  @Operation(
      summary = "Download the profile a token resolves to",
      description =
          "Serves the .ovpn file as an attachment; invalid/expired tokens return a small HTML error page.")
  public ResponseEntity<byte[]> download(@PathVariable String token) {
    try {
      OvpnFile file = profileService.downloadFromToken(token);
      String disposition =
          ContentDisposition.attachment().filename(file.filename()).build().toString();
      return ResponseEntity.ok()
          .contentType(MediaType.parseMediaType(OVPN_CONTENT_TYPE))
          .header("Content-Disposition", disposition)
          .cacheControl(CacheControl.noStore())
          .body(file.content().getBytes(StandardCharsets.UTF_8));
    } catch (ApiException ex) {
      return ResponseEntity.status(ex.getStatus())
          .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
          .body(errorPage(ex.getStatus(), ex.getMessage()));
    }
  }

  private byte[] errorPage(HttpStatus status, String message) {
    String safe = message == null ? "" : escapeHtml(message);
    String title =
        status.value() == HttpStatus.NOT_FOUND.value() ? "Link not found" : "Link expired";
    String html =
        """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>%s</title>
          <style>
            body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
                 background:#0f172a;color:#e2e8f0;display:flex;align-items:center;
                 justify-content:center;height:100vh;margin:0;padding:16px;text-align:center}
            main{max-width:420px}
            h1{font-size:1.25rem;margin:0 0 8px}
            p{color:#94a3b8;margin:0}
          </style>
        </head>
        <body><main><h1>%s</h1><p>%s</p></main></body>
        </html>
        """
            .formatted(title, title, safe);
    return html.getBytes(StandardCharsets.UTF_8);
  }

  private String escapeHtml(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
