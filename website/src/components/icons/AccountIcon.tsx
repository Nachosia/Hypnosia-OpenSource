interface Props { active?: boolean; size?: number; }
export default function AccountIcon({ active = false, size = 24 }: Props) {
  const c = active ? '#E9E9E9' : '#848484';
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none"><path d="M3 6C3 5.45 3.45 5 4 5H9L11 7H20C20.55 7 21 7.45 21 8V18C21 18.55 20.55 19 20 19H4C3.45 19 3 18.55 3 18V6Z" stroke={c} strokeWidth="1.5" strokeLinejoin="round"/><path d="M15 13C15 11.5 16 10.5 17.2 10.5C17.6 10.5 18 10.6 18.3 10.8C18.7 9.2 20.2 8 22 8V9.5C21.2 9.5 20.5 10 20.2 10.7C20.6 11 20.8 11.5 20.8 12C20.8 13.1 19.9 14 18.8 14H15.8C15.3 14 15 13.6 15 13.2V13Z" stroke={c} strokeWidth="1.2" strokeLinejoin="round"/></svg>;
}
