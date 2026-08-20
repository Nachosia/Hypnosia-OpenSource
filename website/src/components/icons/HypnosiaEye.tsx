interface Props { size?: number; color?: string; }
export default function HypnosiaEye({ size = 64, color = '#80FF97' }: Props) {
  return <svg width={size} height={size} viewBox="0 0 64 64" fill="none"><ellipse cx="32" cy="34" rx="26.5" ry="13.5" stroke={color} strokeWidth="1.5"/><line x1="18" y1="34" x2="46" y2="34" stroke={color} strokeWidth="1.5" strokeLinecap="round"/><rect x="28" y="31" width="8" height="6" rx="1.5" stroke={color} strokeWidth="1.5"/><line x1="22" y1="26" x2="22" y2="14" stroke={color} strokeWidth="1.5" strokeLinecap="round"/><line x1="42" y1="26" x2="42" y2="14" stroke={color} strokeWidth="1.5" strokeLinecap="round"/><line x1="32" y1="24" x2="32" y2="12" stroke={color} strokeWidth="1.5" strokeLinecap="round"/></svg>;
}
