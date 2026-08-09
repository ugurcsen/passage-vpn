package com.opnl.vpn.setting;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link UserSetting} entities. */
public interface UserSettingRepository extends JpaRepository<UserSetting, Long> {
  List<UserSetting> findByUserId(String userId);

  Optional<UserSetting> findByUserIdAndKey(String userId, String key);

  void deleteByUserIdAndKey(String userId, String key);
}
