package com.passagevpn.network;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DaemonRepository extends JpaRepository<Daemon, String> {

  Optional<Daemon> findByDaemonIndex(int daemonIndex);

  boolean existsByDaemonIndex(int daemonIndex);

  List<Daemon> findAllByOrderByDaemonIndexAsc();

  List<Daemon> findByEnabledTrueOrderByDaemonIndexAsc();
}
