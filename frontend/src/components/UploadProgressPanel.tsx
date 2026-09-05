import type { ReactNode } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { CheckCircle2 } from 'lucide-react';

export type UploadPanelState = 'idle' | 'uploading' | 'completed';

/**
 * The three-state upload micro-interaction shared by Import.tsx's dropzone and its PDF-password
 * panel: an idle trigger morphs into a live progress bar, then into a brief "Completed"
 * confirmation, all under one `layout` animation so the container resizes smoothly between them
 * instead of jumping. Purely presentational -- the caller owns `state`/`progress` and the timing
 * of when `state` becomes 'completed' (see Import.tsx's `celebrateThenAdvance`), so this component
 * has nothing to fake in a test beyond "given these props, is this on screen".
 */
export function UploadProgressPanel({
  state,
  progress,
  idle,
}: {
  state: UploadPanelState;
  /** 0-100. Only read while `state === 'uploading'`; 100 reads as "Processing statement…" rather
   *  than "Uploading… 100%", since the network transfer finishing is not the server finishing --
   *  see ProgressCallback's own doc comment in api/endpoints.ts. */
  progress: number;
  /** The caller's fully custom idle-state content (drag-and-drop prompt, or a plain submit
   *  button) -- this component only supplies the uploading/completed states and the animated
   *  swap between whichever three are relevant to that call site. */
  idle: ReactNode;
}) {
  const prefersReducedMotion = useReducedMotion();
  const fade = prefersReducedMotion
    ? {}
    : { initial: { opacity: 0 }, animate: { opacity: 1 }, exit: { opacity: 0 }, transition: { duration: 0.2 } };

  return (
    <motion.div layout={!prefersReducedMotion} transition={{ duration: 0.3, ease: 'easeInOut' }}>
      <AnimatePresence mode="wait" initial={false}>
        {state === 'idle' && (
          <motion.div key="idle" {...fade}>
            {idle}
          </motion.div>
        )}

        {state === 'uploading' && (
          <motion.div key="uploading" data-testid="upload-progress" {...fade}>
            <p className="font-medium text-sm text-ink mb-2">
              {progress < 100 ? `Uploading… ${progress}%` : 'Processing statement…'}
            </p>
            <div className="w-full bg-border rounded-full h-2 overflow-hidden">
              <motion.div
                className="bg-primary h-2 rounded-full"
                animate={{ width: `${progress}%` }}
                transition={prefersReducedMotion ? { duration: 0 } : { duration: 0.2, ease: 'easeOut' }}
              />
            </div>
          </motion.div>
        )}

        {state === 'completed' && (
          <motion.div
            key="completed"
            data-testid="upload-completed"
            {...fade}
            className="bg-ink text-card rounded-xl2 py-5 flex flex-col items-center gap-2"
          >
            <CheckCircle2 size={26} className="text-success" />
            <p className="font-medium text-sm">Completed</p>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
}
