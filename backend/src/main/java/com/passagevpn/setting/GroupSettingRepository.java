package com.passagevpn.setting;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link GroupSetting} entities. */
public interface GroupSettingRepository extends JpaRepository<GroupSetting, Long> {
  List<GroupSetting> findByGroupId(String groupId);

  List<GroupSetting> findByGroupIdIn(Collection<String> groupIds);

  Optional<GroupSetting> findByGroupIdAndKey(String groupId, String key);

  void deleteByGroupIdAndKey(String groupId, String key);
}
