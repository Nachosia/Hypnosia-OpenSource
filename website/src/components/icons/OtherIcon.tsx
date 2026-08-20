interface Props { active?: boolean; size?: number; }
export default function OtherIcon({ active = false, size = 24 }: Props) {
  const c = active ? '#E9E9E9' : '#848484';
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none"><circle cx="6" cy="12" r="2.5" stroke={c} strokeWidth="1.5"/><circle cx="18" cy="6" r="2.5" stroke={c} strokeWidth="1.5"/><circle cx="18" cy="18" r="2.5" stroke={c} strokeWidth="1.5"/><path d="M8 10.5L15.5 7M8 13.5L15.5 17" stroke={c} strokeWidth="1.5" strokeLinecap="round"/></svg>;
}
