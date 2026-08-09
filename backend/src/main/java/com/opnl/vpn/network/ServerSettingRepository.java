package com.opnl.vpn.network;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link ServerSetting} entities. */
public interface ServerSettingRepository extends JpaRepository<ServerSetting, String> {}
