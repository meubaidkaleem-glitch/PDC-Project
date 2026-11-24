import { Card, EmptyState, PageHeader } from '../components/ui';

/** Shown for sections whose implementation phase has not been completed yet (docs/implementation-plan.md). */
export function NotYetPage({ title, phase }: { title: string; phase: string }) {
  return (
    <>
      <PageHeader title={title} />
      <Card>
        <EmptyState title="Not implemented yet">
          This section is part of {phase} of the implementation plan and is not available in this build.
        </EmptyState>
      </Card>
    </>
  );
}
