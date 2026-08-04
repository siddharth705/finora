import type { AdminUpdateUserRequest } from '../../types';

export function errorMessage(err: any, fallback: string) {
  return err?.response?.data?.message ?? fallback;
}

/** Edits fullName/phoneNumber/lowBalanceThreshold/timezone -- deliberately not email or password,
 *  same scope AdminUpdateUserRequest allows on the backend (see AdminDtos.java's comment there). */
