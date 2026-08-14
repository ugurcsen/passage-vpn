package com.opnl.vpn.network;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpenVpnNodeRepository extends JpaRepository<OpenVpnNode, String> {

  Optional<OpenVpnNode> findByNameIgnoreCase(String name);

  boolean existsByNameIgnoreCase(String name);

  List<OpenVpnNode> findByEnabledTrueOrderByCreatedAtAsc();

  List<OpenVpnNode> findAllByOrderByCreatedAtAsc();
}
