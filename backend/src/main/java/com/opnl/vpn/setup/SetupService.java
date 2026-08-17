package com.opnl.vpn.setup;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.common.AppMeta;
import com.opnl.vpn.common.AppMetaRepository;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.network.ServerConfig;
import com.opnl.vpn.network.ServerConfigGenerator;
import com.opnl.vpn.network.ServerSetting;
import com.opnl.vpn.network.ServerSettingRepository;
import com.opnl.vpn.pki.EasyRsaService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** First-run setup state machine. Steps must run in order. */
@Slf4j
@Service
public class SetupService {

  public enum State {
    NOT_STARTED,
    ADMIN_DONE,
    SERVER_DONE,
    PKI_DONE,
    COMPLETE
  }

  private static final String STATE_KEY = "setup.state";

  private final AppMetaRepository metaRepository;
  private final UserRepository userRepository;
  private final ServerSettingRepository settingRepository;
  private final PasswordEncoder passwordEncoder;
  private final EasyRsaService easyRsa;
  private final ServerConfigGenerator configGenerator;
  private final DaemonService daemonService;

  public SetupService(
      AppMetaRepository metaRepository,
      UserRepository userRepository,
      ServerSettingRepository settingRepository,
      PasswordEncoder passwordEncoder,
      EasyRsaService easyRsa,
      ServerConfigGenerator configGenerator,
      DaemonService daemonService) {
    this.metaRepository = metaRepository;
    this.userRepository = userRepository;
    this.settingRepository = settingRepository;
    this.passwordEncoder = passwordEncoder;
    this.easyRsa = easyRsa;
    this.configGenerator = configGenerator;
    this.daemonService = daemonService;
  }

  public State state() {
    return metaRepository
        .findById(STATE_KEY)
        .map(m -> State.valueOf(m.getValue()))
        .orElse(State.NOT_STARTED);
  }

  public boolean complete() {
    return state() == State.COMPLETE;
  }

  public SetupStatus status() {
    return new SetupStatus(
        state(),
        state() == State.NOT_STARTED || state() == State.ADMIN_DONE,
        easyRsa.isInitialized());
  }

  /** Executes one wizard step. See {@code SetupWizardRequest}. */
  @Transactional
  public void runStep(String step, com.fasterxml.jackson.databind.JsonNode payload) {
    com.fasterxml.jackson.databind.ObjectMapper om =
        new com.fasterxml.jackson.databind.ObjectMapper();
    switch (step) {
      case "admin" -> createAdmin(om.convertValue(payload, AdminPayload.class));
      case "server" -> saveServerConfig(om.convertValue(payload, ServerConfig.class));
      case "pki" -> provisionPki();
      default -> throw ApiException.badRequest("setup_step", "Unknown setup step: " + step);
    }
  }

  private void createAdmin(AdminPayload payload) {
    if (state() != State.NOT_STARTED) {
      throw ApiException.conflict("setup_already_started", "Setup has already been started");
    }
    if (userRepository.existsByUsername(payload.username())) {
      throw ApiException.conflict("username_taken", "Username is already taken");
    }
    if (payload.password() == null || payload.password().length() < 8) {
      throw ApiException.badRequest("weak_password", "Password must be at least 8 characters");
    }
    userRepository.save(
        User.builder()
            .id(UUID.randomUUID().toString())
            .username(payload.username())
            .passwordHash(passwordEncoder.encode(payload.password()))
            .role(User.Role.ADMIN)
            .createdAt(Instant.now())
            .build());
    setState(State.ADMIN_DONE);
    log.info("Setup step 'admin' completed");
  }

  private void saveServerConfig(ServerConfig config) {
    requireState(State.ADMIN_DONE);
    settingRepository.save(new ServerSetting("network", configGenerator.toJson(config)));
    daemonService.createOrUpdatePrimary(config);
    setState(State.SERVER_DONE);
    log.info("Setup step 'server' completed: {}", config);
  }

  private void provisionPki() {
    requireState(State.SERVER_DONE);
    easyRsa.initPki();
    easyRsa.buildServerCert("server");
    easyRsa.genCrl();

    daemonService.writeAll();
    setState(State.COMPLETE);
    log.info("Setup step 'pki' completed; PKI provisioned and daemon configs written");
  }

  public ServerConfig currentServerConfig() {
    return settingRepository
        .findById("network")
        .map(s -> configGenerator.fromJson(s.getValue()))
        .orElseGet(ServerConfig::defaults);
  }

  private void requireState(State expected) {
    if (state() != expected) {
      throw ApiException.conflict(
          "setup_state", "Expected state " + expected + " but was " + state());
    }
  }

  private void setState(State state) {
    metaRepository.save(AppMeta.of(STATE_KEY, state.name()));
  }

  public record AdminPayload(String username, String password) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SetupStatus(State state, boolean adminStepRequired, boolean pkiInitialized) {}
}
