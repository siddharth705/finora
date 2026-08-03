/**
 * Route params for the unauthenticated stack. Kept in its own module so screens can type their
 * props without importing the navigator itself (which imports the screens -- a cycle).
 */
export type AuthStackParamList = {
  Login: { message?: string } | undefined;
  Register: undefined;
  ForgotPassword: undefined;
};
