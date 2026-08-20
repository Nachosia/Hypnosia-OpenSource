interface Props { active?: boolean; size?: number; }
export default function HomeIcon({ active = false, size = 24 }: Props) {
  const c = active ? '#E9E9E9' : '#848484';
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none"><path d="M4 10.5V20.5C4 21.03 4.47 21.5 5 21.5H10V15.5H14V21.5H19C19.53 21.5 20 21.03 20 20.5V10.5" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/><path d="M2 12L12 2.5L22 12" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/></svg>;
}
