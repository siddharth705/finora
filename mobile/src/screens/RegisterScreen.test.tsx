import { render, screen } from '@testing-library/react-native';
import { RegisterScreen } from './RegisterScreen';
import { ThemeProvider } from '../theme';
import type { AuthStackParamList } from '../navigation/types';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';

/**
 * Scoped to the Phase 3B prefill behaviour -- the one piece of RegisterScreen's logic this test
 * file exists for. Field validation and submission have no behaviour of their own beyond what
 * AuthContext.test.tsx already covers on the register() side.
 */

const mockRegister = jest.fn();
const mockLoginWithGoogle = jest.fn();
const mockLoginWithApple = jest.fn();
jest.mock('../context/AuthContext', () => ({
  useAuth: () => ({
    register: mockRegister,
    loginWithGoogle: mockLoginWithGoogle,
    loginWithApple: mockLoginWithApple,
  }),
}));

type Props = NativeStackScreenProps<AuthStackParamList, 'Register'>;

const mockNavigate = jest.fn();

function renderScreen(params?: { email?: string; phoneNumber?: string }) {
  const navigation = { navigate: mockNavigate } as unknown as Props['navigation'];
  const route = { key: 'Register', name: 'Register', params } as Props['route'];
  return render(
    <ThemeProvider>
      <RegisterScreen navigation={navigation} route={route} />
    </ThemeProvider>
  );
}

// Phase 3B: AuthEntryScreen sends whichever field the identifier looked like once it learns
// nextAction is CONTINUE (no existing account) -- prefilled here so the user doesn't have to
// retype what they already entered on the entry screen.
describe('RegisterScreen prefill from AuthEntry', () => {
  it('prefills the email field when arriving with an email in route params', () => {
    renderScreen({ email: 'jane@example.com' });

    expect(screen.getByLabelText('Email').props.value).toBe('jane@example.com');
  });

  it('prefills the mobile number field, stripped to its local 10 digits, when arriving with a phone number in route params', () => {
    const fakePhone = '+919876543210'; // synthetic-ok: same fake sequential number used throughout this app's test fixtures
    renderScreen({ phoneNumber: fakePhone });

    expect(screen.getByLabelText('Mobile number').props.value).toBe('9876543210' /* synthetic-ok */);
  });

  it('leaves both fields empty on an ordinary direct visit with no route params', () => {
    renderScreen();

    expect(screen.getByLabelText('Email').props.value).toBe('');
    expect(screen.getByLabelText('Mobile number').props.value).toBe('');
  });
});
