interface Props { active?: boolean; size?: number; }
export default function HUDIcon({ active = false, size = 24 }: Props) {
  const c = active ? '#E9E9E9' : '#848484';
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none"><rect x="3" y="3" width="7.5" height="7.5" rx="1" stroke={c} strokeWidth="1.5"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="1" stroke={c} strokeWidth="1.5"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="1" stroke={c} strokeWidth="1.5"/><path d="M15.5 15.5H19M17.25 13.75V17.25" stroke={c} strokeWidth="1.5" strokeLinecap="round"/></svg>;
}
