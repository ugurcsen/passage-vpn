import { useState, type ReactNode } from "react";
import { Outlet, useLocation, useNavigate } from "react-router-dom";
import {
  AppBar,
  Box,
  CssBaseline,
  Drawer,
  IconButton,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Menu,
  MenuItem,
  Toolbar,
  Tooltip,
  Typography,
} from "@mui/material";
import MenuIcon from "@mui/icons-material/Menu";
import LightModeIcon from "@mui/icons-material/LightMode";
import DarkModeIcon from "@mui/icons-material/DarkMode";
import DashboardIcon from "@mui/icons-material/Dashboard";
import PeopleIcon from "@mui/icons-material/People";
import GroupIcon from "@mui/icons-material/Group";
import VpnKeyIcon from "@mui/icons-material/VpnKey";
import DownloadIcon from "@mui/icons-material/Download";
import DnsIcon from "@mui/icons-material/Dns";
import LanguageIcon from "@mui/icons-material/Language";
import HubIcon from "@mui/icons-material/Hub";
import MonitorHeartIcon from "@mui/icons-material/MonitorHeart";
import SettingsIcon from "@mui/icons-material/Settings";
import SecurityIcon from "@mui/icons-material/Security";
import HistoryIcon from "@mui/icons-material/History";
import KeyIcon from "@mui/icons-material/Key";
import PersonIcon from "@mui/icons-material/Person";
import AccountCircleIcon from "@mui/icons-material/AccountCircle";
import LogoutIcon from "@mui/icons-material/Logout";
import PaletteIcon from "@mui/icons-material/Palette";
import FactCheckIcon from "@mui/icons-material/FactCheck";
import BackupIcon from "@mui/icons-material/Backup";
import BuildIcon from "@mui/icons-material/Build";
import { useAuth, type Role } from "@/hooks/useAuth";
import { useBrand } from "@/hooks/useBrand";
import { canAccess } from "@/lib/roles";

const DRAWER_WIDTH = 240;

interface NavItem {
  label: string;
  path: string;
  icon: ReactNode;
  /** Roles allowed to see the item; undefined means all authenticated roles. */
  roles?: Role[];
}

const NAV_ITEMS: NavItem[] = [
  { label: "Dashboard", path: "/", icon: <DashboardIcon />, roles: ["ADMIN"] },
  { label: "Users", path: "/users", icon: <PeopleIcon />, roles: ["ADMIN", "GROUP_ADMIN"] },
  { label: "Groups", path: "/groups", icon: <GroupIcon />, roles: ["ADMIN", "GROUP_ADMIN"] },
  { label: "Certificates", path: "/certs", icon: <VpnKeyIcon />, roles: ["ADMIN"] },
  { label: "Access Rules", path: "/rules", icon: <SecurityIcon />, roles: ["ADMIN"] },
  { label: "DNS Overrides", path: "/dns", icon: <LanguageIcon />, roles: ["ADMIN"] },
  { label: "Connection Profiles", path: "/profiles", icon: <DownloadIcon />, roles: ["ADMIN"] },
  { label: "VPN Daemons", path: "/daemons", icon: <DnsIcon />, roles: ["ADMIN"] },
  { label: "VPN Nodes", path: "/nodes", icon: <HubIcon />, roles: ["ADMIN"] },
  { label: "My Profiles", path: "/portal", icon: <PersonIcon /> },
  { label: "My Account", path: "/portal/account", icon: <SecurityIcon /> },
  { label: "Live Status", path: "/status", icon: <MonitorHeartIcon />, roles: ["ADMIN"] },
  { label: "Connection Logs", path: "/connection-logs", icon: <HistoryIcon />, roles: ["ADMIN", "GROUP_ADMIN"] },
  { label: "Settings", path: "/settings", icon: <SettingsIcon />, roles: ["ADMIN"] },
  { label: "Branding", path: "/branding", icon: <PaletteIcon />, roles: ["ADMIN"] },
  { label: "Config Report", path: "/config-report", icon: <FactCheckIcon />, roles: ["ADMIN"] },
  { label: "Backups", path: "/backups", icon: <BackupIcon />, roles: ["ADMIN"] },
  { label: "Maintenance", path: "/maintenance", icon: <BuildIcon />, roles: ["ADMIN"] },
  { label: "Audit Log", path: "/audit-logs", icon: <HistoryIcon />, roles: ["ADMIN"] },
  { label: "API Tokens", path: "/api-tokens", icon: <KeyIcon />, roles: ["ADMIN"] },
];

interface AppLayoutProps {
  darkMode: boolean;
  onToggleDarkMode: () => void;
}

/** Application shell: responsive drawer navigation + top bar. */
export function AppLayout({ darkMode, onToggleDarkMode }: AppLayoutProps) {
  const [mobileOpen, setMobileOpen] = useState(false);
  const [accountMenu, setAccountMenu] = useState<HTMLElement | null>(null);
  const { user, logout } = useAuth();
  const brand = useBrand();
  const navigate = useNavigate();
  const location = useLocation();

  const drawer = (
    <Box>
      <Toolbar sx={{ px: 2 }}>
        <Typography variant="h6" fontWeight={700} color="primary" noWrap>
          {brand.name}
        </Typography>
      </Toolbar>
      <List dense>
        {NAV_ITEMS.filter((item) => canAccess(item.roles, user?.role ?? "USER")).map((item) => (
          <ListItemButton
            key={item.path}
            selected={location.pathname === item.path}
            onClick={() => {
              navigate(item.path);
              setMobileOpen(false);
            }}
          >
            <ListItemIcon>{item.icon}</ListItemIcon>
            <ListItemText primary={item.label} />
          </ListItemButton>
        ))}
      </List>
    </Box>
  );

  return (
    <Box sx={{ display: "flex", minHeight: "100vh" }}>
      <CssBaseline />
      <AppBar position="fixed" sx={{ width: { md: `calc(100% - ${DRAWER_WIDTH}px)` }, ml: { md: `${DRAWER_WIDTH}px` } }}>
        <Toolbar>
          <IconButton
            color="inherit"
            edge="start"
            onClick={() => setMobileOpen(true)}
            sx={{ mr: 2, display: { md: "none" } }}
          >
            <MenuIcon />
          </IconButton>
          <Typography variant="body1" noWrap sx={{ flexGrow: 1 }}>
            {NAV_ITEMS.find((i) => i.path === location.pathname)?.label ?? brand.name}
          </Typography>
          <Tooltip title="Toggle theme">
            <IconButton color="inherit" onClick={onToggleDarkMode}>
              {darkMode ? <LightModeIcon /> : <DarkModeIcon />}
            </IconButton>
          </Tooltip>
          <Tooltip title="Account">
            <IconButton color="inherit" onClick={(e) => setAccountMenu(e.currentTarget)}>
              <AccountCircleIcon />
            </IconButton>
          </Tooltip>
          <Menu anchorEl={accountMenu} open={!!accountMenu} onClose={() => setAccountMenu(null)}>
            <MenuItem disabled>
              {user?.fullName || user?.username} ({user?.role})
            </MenuItem>
            <MenuItem
              onClick={() => {
                setAccountMenu(null);
                logout().then(() => navigate("/login"));
              }}
            >
              <ListItemIcon>
                <LogoutIcon fontSize="small" />
              </ListItemIcon>
              <ListItemText>Sign out</ListItemText>
            </MenuItem>
          </Menu>
        </Toolbar>
      </AppBar>
      <Box component="nav" sx={{ width: { md: DRAWER_WIDTH }, flexShrink: { md: 0 } }}>
        <Drawer
          variant="temporary"
          open={mobileOpen}
          onClose={() => setMobileOpen(false)}
          ModalProps={{ keepMounted: true }}
          sx={{ display: { xs: "block", md: "none" }, "& .MuiDrawer-paper": { width: DRAWER_WIDTH } }}
        >
          {drawer}
        </Drawer>
        <Drawer
          variant="permanent"
          open
          sx={{ display: { xs: "none", md: "block" }, "& .MuiDrawer-paper": { width: DRAWER_WIDTH, boxSizing: "border-box" } }}
        >
          {drawer}
        </Drawer>
      </Box>
      <Box
        component="main"
        sx={{
          flexGrow: 1,
          minWidth: 0,
          overflowX: "hidden",
          p: 3,
          width: { md: `calc(100% - ${DRAWER_WIDTH}px)` },
        }}
      >
        <Toolbar />
        <PageOutlet />
      </Box>
    </Box>
  );
}

function PageOutlet() {
  return <Outlet />;
}

export type { ReactNode };
