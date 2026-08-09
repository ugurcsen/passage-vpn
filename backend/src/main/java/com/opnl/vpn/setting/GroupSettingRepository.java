package com.opnl.vpn.setting;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link GroupSetting} entities. */
public interface GroupSettingRepository extends JpaRepository<GroupSetting, Long> {
  List<GroupSetting> findByGroupId(String groupId);

  Optional<GroupSetting> findByGroupIdAndKey(String groupId, String key);

  void deleteByGroupIdAndKey(String groupId, String key);
}
