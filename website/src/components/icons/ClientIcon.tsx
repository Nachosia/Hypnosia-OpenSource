interface Props { active?: boolean; size?: number; }
export default function ClientIcon({ active = false, size = 24 }: Props) {
  const c = active ? '#E9E9E9' : '#848484';
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none"><path d="M4 5C4 4.45 4.45 4 5 4H9V20H5C4.45 20 4 19.55 4 19V5Z" stroke={c} strokeWidth="1.5" strokeLinejoin="round"/><path d="M15 4H19C19.55 4 20 4.45 20 5V19C20 19.55 19.55 20 19 20H15V4Z" stroke={c} strokeWidth="1.5" strokeLinejoin="round"/><line x1="12" y1="4" x2="12" y2="20" stroke={c} strokeWidth="1.5" strokeLinecap="round"/></svg>;
}
