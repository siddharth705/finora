import {
  Tag, Home, ShoppingCart, Utensils, Car, Zap, ShoppingBag, HeartPulse, Film, TrendingUp,
  Percent, Repeat, Users, Landmark, Shield, GraduationCap, RefreshCw, Plane, Gift, PawPrint,
  Sofa, Receipt, Banknote, Briefcase, ArrowDownCircle,
} from 'lucide-react';

// Maps the curated icon-token vocabulary CategoryPalette.ICONS defines server-side to already-
// imported lucide-react components -- lucide-react components can't be looked up by string name
// at runtime without importing every one, so this is a small closed map instead. Every default
// category (the V118 migration's backfill) and every user-created one (CategoryPalette's own
// validation) draws its icon token from exactly this set, so nothing here should ever miss.
export const ICON_COMPONENTS: Record<string, any> = {
  tag: Tag, home: Home, 'shopping-cart': ShoppingCart, utensils: Utensils, car: Car, zap: Zap,
  'shopping-bag': ShoppingBag, 'heart-pulse': HeartPulse, film: Film, 'trending-up': TrendingUp,
  percent: Percent, repeat: Repeat, users: Users, landmark: Landmark, shield: Shield,
  'graduation-cap': GraduationCap, 'refresh-cw': RefreshCw, plane: Plane, gift: Gift,
  'paw-print': PawPrint, sofa: Sofa, receipt: Receipt, banknote: Banknote, briefcase: Briefcase,
  'arrow-down-circle': ArrowDownCircle,
};

// Same 9 hex values as CategoryPalette.COLORS server-side -- the frontend keeps its own copy
// since /categories (and /categories/options) return the color TOKEN, not a CSS-ready hex string.
export const COLOR_HEX: Record<string, string> = {
  gray: '#6b7280', blue: '#2563eb', green: '#16a34a', red: '#dc2626', orange: '#ea580c',
  yellow: '#d97706', purple: '#7c3aed', pink: '#db2777', teal: '#0d9488',
};
