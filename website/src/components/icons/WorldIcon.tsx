interface Props { active?: boolean; size?: number; }
export default function WorldIcon({ active = false, size = 24 }: Props) {
  const c = active ? '#E9E9E9' : '#848484';
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="6" stroke={c} strokeWidth="1.5"/><ellipse cx="12" cy="12" rx="10" ry="4" stroke={c} strokeWidth="1.5" transform="rotate(-20 12 12)"/></svg>;
}
