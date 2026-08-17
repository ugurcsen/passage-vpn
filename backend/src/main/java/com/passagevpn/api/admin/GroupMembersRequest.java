package com.passagevpn.api.admin;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Payload for replacing a group's member list. */
public record GroupMembersRequest(@NotEmpty @Size(max = 1000) List<String> userIds) {}
