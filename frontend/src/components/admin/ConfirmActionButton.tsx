import type { ReactNode } from 'react';

type ConfirmActionButtonProps = {
  children: ReactNode;
  confirmText: string;
  loading?: boolean;
  disabled?: boolean;
  tone?: 'primary' | 'danger' | 'ghost';
  onConfirm: () => void | Promise<void>;
};

const toneMap = {
  primary: 'bg-primary-600 text-white hover:bg-primary-700',
  danger: 'bg-rose-600 text-white hover:bg-rose-700',
  ghost: 'bg-slate-100 text-slate-700 hover:bg-slate-200 dark:bg-slate-800 dark:text-slate-200 dark:hover:bg-slate-700',
};

export default function ConfirmActionButton({
  children,
  confirmText,
  loading = false,
  disabled = false,
  tone = 'ghost',
  onConfirm,
}: ConfirmActionButtonProps) {
  const handleClick = async () => {
    if (!window.confirm(confirmText)) {
      return;
    }
    await onConfirm();
  };

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={disabled || loading}
      className={`inline-flex items-center justify-center rounded-lg px-3 py-1.5 text-xs font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${toneMap[tone]}`}
    >
      {loading ? '处理中...' : children}
    </button>
  );
}
