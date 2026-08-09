package com.opnl.vpn.common;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for the {@link AppMeta} key/value store. */
public interface AppMetaRepository extends JpaRepository<AppMeta, String> {}
