// Full implementation lands in Task 9-11 (Welcome/FinancialFocus/TourIntro/Success). The 'tour'
// step itself is deliberately NOT rendered by this component -- ProtectedRoute renders the real
// app + TourOverlay for that step instead, so the tour spotlights the live Sidebar rather than a
// copy of it. This stub exists only so ProtectedRoute has something real to render and test
// against in this task.
export function OnboardingFlow() {
  return <div data-testid="onboarding-flow">Onboarding flow placeholder</div>;
}
