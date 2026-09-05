import { act, fireEvent, render, screen } from '@testing-library/react-native';
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

// Refer & Earn MVP: mobile has no `?ref=` URL param to read a code from (unlike web), so this
// field is the only way a mobile signup can redeem one. Uppercased as typed to match the backend's
// own stored format (ReferralService.generateUniqueCode) and sent as undefined, not '', when left
// blank -- see RegisterScreen's own comment on that call site.
describe('RegisterScreen referral code field', () => {
  it('uppercases the referral code as it is typed', () => {
    renderScreen();

    fireEvent.changeText(screen.getByLabelText('Referral code (optional)'), 'ab12cd34');

    expect(screen.getByLabelText('Referral code (optional)').props.value).toBe('AB12CD34');
  });

  it('passes the typed referral code through to register() on submit', async () => {
    renderScreen();
    fillValidForm();
    fireEvent.changeText(screen.getByLabelText('Referral code (optional)'), 'ab12cd34');

    fireEvent.press(screen.getByText('Create account'));
    await settle();

    expect(mockRegister).toHaveBeenCalledWith(
      'jane@example.com', 'Str0ng!Pass', 'Jane Doe', '+919876543210' /* synthetic-ok */, 'AB12CD34'
    );
  });

  it('passes undefined, not an empty string, when the referral code is left blank', async () => {
    renderScreen();
    fillValidForm();

    fireEvent.press(screen.getByText('Create account'));
    await settle();

    expect(mockRegister).toHaveBeenCalledWith(
      'jane@example.com', 'Str0ng!Pass', 'Jane Doe', '+919876543210' /* synthetic-ok */, undefined
    );
  });
});

function fillValidForm() {
  fireEvent.changeText(screen.getByLabelText('Full name'), 'Jane Doe');
  fireEvent.changeText(screen.getByLabelText('Email'), 'jane@example.com');
  fireEvent.changeText(screen.getByLabelText('Mobile number'), '9876543210' /* synthetic-ok */);
  fireEvent.changeText(screen.getByLabelText('Password (min 8 characters)'), 'Str0ng!Pass');
  fireEvent.changeText(screen.getByLabelText('Confirm password'), 'Str0ng!Pass');
}

/** Lets handleSubmit's `finally` setState land before assertions run -- same helper as
 *  LoginScreen.test.tsx's own settle(). */
async function settle() {
  await act(async () => {});
}
