import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import Ionicons from '@expo/vector-icons/Ionicons';
import { DashboardScreen } from '../screens/DashboardScreen';
import { LedgerScreen } from '../screens/LedgerScreen';
import { AccountsScreen } from '../screens/AccountsScreen';
import { ImportScreen } from '../screens/import/ImportScreen';
import { MoreScreen } from '../screens/MoreScreen';
import { useTheme } from '../theme';
import type { AppTabParamList, MoreStackParamList } from './types';

const Tab = createBottomTabNavigator<AppTabParamList>();
const MoreStack = createNativeStackNavigator<MoreStackParamList>();

function MoreNavigator() {
  const c = useTheme();
  return (
    <MoreStack.Navigator
      screenOptions={{
        headerStyle: { backgroundColor: c.bg },
        headerTintColor: c.ink,
        headerShadowVisible: false,
      }}
    >
      {/* Header hidden on the menu itself (it renders its own title), shown on pushed screens so
          there's a native back affordance. */}
      <MoreStack.Screen name="MoreHome" component={MoreScreen} options={{ headerShown: false }} />
      <MoreStack.Screen name="Accounts" component={AccountsScreen} options={{ headerShown: false }} />
    </MoreStack.Navigator>
  );
}

const TAB_ICON: Record<keyof AppTabParamList, { active: string; inactive: string }> = {
  Home: { active: 'home', inactive: 'home-outline' },
  Transactions: { active: 'swap-horizontal', inactive: 'swap-horizontal-outline' },
  Import: { active: 'add-circle', inactive: 'add-circle-outline' },
  More: { active: 'menu', inactive: 'menu-outline' },
};

export function AppTabs() {
  const c = useTheme();
  return (
    <Tab.Navigator
      screenOptions={({ route }) => ({
        headerShown: false,
        tabBarActiveTintColor: c.primary,
        tabBarInactiveTintColor: c.muted,
        tabBarStyle: { backgroundColor: c.card, borderTopColor: c.border },
        tabBarIcon: ({ focused, color, size }) => {
          const icons = TAB_ICON[route.name];
          return (
            <Ionicons name={(focused ? icons.active : icons.inactive) as any} size={size} color={color} />
          );
        },
      })}
    >
      <Tab.Screen name="Home" component={DashboardScreen} />
      <Tab.Screen name="Transactions" component={LedgerScreen} />
      {/* Sits centre-left of More rather than as a floating action button: importing a statement
          is a deliberate, occasional task, not a one-tap action, and it has a full screen behind it. */}
      <Tab.Screen name="Import" component={ImportScreen} />
      <Tab.Screen name="More" component={MoreNavigator} />
    </Tab.Navigator>
  );
}
