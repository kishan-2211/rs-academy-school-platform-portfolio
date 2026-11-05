import type { SVGProps } from "react";

type Props = SVGProps<SVGSVGElement>;
const base = { width: 20, height: 20, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 1.7, strokeLinecap: "round" as const, strokeLinejoin: "round" as const, "aria-hidden": true };

export function OverviewIcon(props: Props) { return <svg {...base} {...props}><rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="4" rx="2"/><rect x="14" y="11" width="7" height="10" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/></svg>; }
export function PeopleIcon(props: Props) { return <svg {...base} {...props}><circle cx="9" cy="8" r="3"/><path d="M3.5 20a5.5 5.5 0 0 1 11 0M16 4.5a3 3 0 0 1 0 6M17 14.5a5.5 5.5 0 0 1 3.5 5.5"/></svg>; }
export function AcademicIcon(props: Props) { return <svg {...base} {...props}><path d="m3 8 9-5 9 5-9 5-9-5Z"/><path d="M7 11v5c3 2.5 7 2.5 10 0v-5M21 8v6"/></svg>; }
export function MoneyIcon(props: Props) { return <svg {...base} {...props}><rect x="3" y="5" width="18" height="14" rx="3"/><path d="M7 15h3M3 10h18M16 15h1"/></svg>; }
export function MessageIcon(props: Props) { return <svg {...base} {...props}><path d="M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4v8Z"/><path d="M8 9h8M8 13h5"/></svg>; }
export function ReportIcon(props: Props) { return <svg {...base} {...props}><path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/></svg>; }
export function SettingsIcon(props: Props) { return <svg {...base} {...props}><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.6v.2h-4V21a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9A1.7 1.7 0 0 0 3 14H2.8v-4H3a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1A1.7 1.7 0 0 0 9 4.6 1.7 1.7 0 0 0 10 3v-.2h4V3a1.7 1.7 0 0 0 1 1.6 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.6 1h.2v4H21a1.7 1.7 0 0 0-1.6 1Z"/></svg>; }
export function SearchIcon(props: Props) { return <svg {...base} {...props}><circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/></svg>; }
export function BellIcon(props: Props) { return <svg {...base} {...props}><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/></svg>; }
export function ChevronIcon(props: Props) { return <svg {...base} {...props}><path d="m9 7 5 5-5 5"/></svg>; }
export function MenuIcon(props: Props) { return <svg {...base} {...props}><path d="M4 7h16M4 12h16M4 17h16"/></svg>; }
export function PlusIcon(props: Props) { return <svg {...base} {...props}><path d="M12 5v14M5 12h14"/></svg>; }
export function AttendanceIcon(props: Props) { return <svg {...base} {...props}><rect x="3" y="5" width="18" height="16" rx="3"/><path d="M8 3v4M16 3v4M3 10h18m-12 5 2 2 4-4"/></svg>; }
