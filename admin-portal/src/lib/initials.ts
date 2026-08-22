// Shared by Sidebar's footer avatar and AdminLayout's header avatar -- was defined identically
// in both once AdminLayout grew its own avatar circle, same duplication Sidebar.tsx originally had.
export function initials(name: string | null) {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? '') + (parts[1]?.[0] ?? '')).toUpperCase();
}
