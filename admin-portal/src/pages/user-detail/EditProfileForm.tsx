import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Phone } from 'lucide-react';
import { adminUsersApi } from '../../api/endpoints';
import type { AdminUpdateUserRequest, CreateAccountRequest } from '../../types';
import { errorMessage } from './errorMessage';

export function EditProfileForm({
  userId, initial, onDone,
}: {
  userId: string;
  initial: { fullName: string; phoneNumber: string | null };
  onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const [fullName, setFullName] = useState(initial.fullName);
  const [phoneNumber, setPhoneNumber] = useState(initial.phoneNumber ?? '');
  const [error, setError] = useState<string | null>(null);

  const updateMutation = useMutation({
    mutationFn: (req: AdminUpdateUserRequest) => adminUsersApi.update(userId, req),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin-user', userId] });
      void queryClient.invalidateQueries({ queryKey: ['admin-users'] });
      onDone();
    },
    onError: (err: any) => setError(errorMessage(err, 'Failed to update profile.')),
  });

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        updateMutation.mutate({ fullName: fullName.trim(), phoneNumber: phoneNumber.trim() });
      }}
      className="bg-bg border border-border rounded-xl2 p-4 space-y-3 mt-4"
    >
      {error && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3 py-2">{error}</p>}
      <div className="grid gap-3 md:grid-cols-2">
        <div>
          <label className="text-xs font-medium text-muted mb-1 block">Full name</label>
          <input
            required
            value={fullName}
            onChange={(e) => setFullName(e.target.value)}
            className="w-full bg-card border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label className="text-xs font-medium text-muted mb-1 block">Phone number</label>
          <input
            required
            value={phoneNumber}
            onChange={(e) => setPhoneNumber(e.target.value)}
            className="w-full bg-card border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
      </div>
      <div className="flex justify-end gap-2">
        <button type="button" onClick={onDone} className="text-sm font-medium text-muted px-3.5 py-2 rounded-lg hover:bg-card">
          Cancel
        </button>
        <button
          type="submit"
          disabled={updateMutation.isPending}
          className="bg-primary hover:bg-primary-dark text-white text-sm font-semibold rounded-lg px-4 py-2 disabled:opacity-50"
        >
          Save
        </button>
      </div>
    </form>
  );
}

const BLANK_ACCOUNT: CreateAccountRequest = {
  name: '', accountType: 'SAVINGS', balance: 0, bankId: '', accountHolderName: '', accountNumberMasked: '',
};
