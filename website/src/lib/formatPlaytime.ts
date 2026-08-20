/**
 * Format total minutes into "Xч Yм" or "Yм" string.
 * Examples: 125 → "2ч 5м", 45 → "45м", 0 → "0м"
 */
export function formatPlaytime(totalMinutes: number): string {
  const mins = Math.max(0, Math.floor(totalMinutes));
  const hours = Math.floor(mins / 60);
  const minutes = mins % 60;
  if (hours > 0 && minutes > 0) return `${hours}ч ${minutes}м`;
  if (hours > 0) return `${hours}ч`;
  return `${minutes}м`;
}
