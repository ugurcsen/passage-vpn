import type { Daemon } from "@/lib/api";

export interface DaemonForm {
  name: string;
  daemonIndex: string;
  port: string;
  proto: "udp" | "tcp" | "udp6" | "tcp6";
  subnet: string;
  subnetMask: string;
  dnsServers: string;
  domain: string;
  extraRoutes: string[];
  fullTunnel: boolean;
  clientCertNotRequired: boolean;
  authUserPass: boolean;
  adminHost: string;
  nodeId: string;
  ipv6Enabled: boolean;
  ipv6Subnet: string;
  enabled: boolean;
}

export const EMPTY_FORM: DaemonForm = {
  name: "",
  daemonIndex: "",
  port: "",
  proto: "udp",
  subnet: "",
  subnetMask: "255.255.255.0",
  dnsServers: "1.1.1.1, 8.8.8.8",
  domain: "",
  extraRoutes: [],
  fullTunnel: true,
  clientCertNotRequired: false,
  authUserPass: true,
  adminHost: "",
  nodeId: "",
  ipv6Enabled: false,
  ipv6Subnet: "fd00:1::/64",
  enabled: true,
};

/** Profile types a daemon serves, derived from its flag combination. */
export function daemonRole(d: Daemon): string {
  if (d.clientCertNotRequired) return "Generic";
  if (!d.authUserPass) return "Auto-login";
  return "User-locked / Server-locked";
}

export function dcoLabel(d: Daemon): string {
  if (d.dco === true) return "DCO";
  if (d.dco === false) return "Userspace";
  return "—";
}

export function splitList(value: string): string[] {
  return value
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

const IPV4_CIDR = /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\/\d{1,2}$/;
const IPV6_CIDR = /^[0-9a-fA-F:.]+\/\d{1,3}$/;

export function isValidCidr(value: string): boolean {
  return IPV4_CIDR.test(value) || IPV6_CIDR.test(value);
}

export function rowToForm(row: Daemon): DaemonForm {
  return {
    name: row.name ?? "",
    daemonIndex: String(row.daemonIndex),
    port: String(row.port),
    proto: row.proto,
    subnet: row.subnet,
    subnetMask: row.subnetMask,
    dnsServers: row.dnsServers.join(", "),
    domain: row.domain ?? "",
    extraRoutes: [...row.extraRoutes],
    fullTunnel: row.fullTunnel,
    clientCertNotRequired: row.clientCertNotRequired,
    authUserPass: row.authUserPass,
    adminHost: row.adminHost ?? "",
    nodeId: row.nodeId ?? "",
    ipv6Enabled: row.ipv6Enabled,
    ipv6Subnet: row.ipv6Subnet ?? "fd00:1::/64",
    enabled: row.enabled,
  };
}
