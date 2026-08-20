interface Props { active?: boolean; size?: number; }
export default function VisualIcon({ active = false, size = 24 }: Props) {
  const c = active ? '#E9E9E9' : '#848484';
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="5" stroke={c} strokeWidth="1.5"/><path d="M12 3V6M12 18V21M3 12H6M18 12H21M5.64 5.64L7.76 7.76M16.24 16.24L18.36 18.36M5.64 18.36L7.76 16.24M16.24 7.76L18.36 5.64" stroke={c} strokeWidth="1.5" strokeLinecap="round"/></svg>;
}
