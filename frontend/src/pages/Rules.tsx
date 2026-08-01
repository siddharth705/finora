import { useEffect, useState, type ReactNode } from 'react';
import { SlidersHorizontal, Users, Trash2, Pencil, GitMerge, ListChecks, X } from 'lucide-react';
import { rulesApi, relationshipsApi } from '../api/endpoints';
import type { Rule, Relationship, Transaction } from '../types';

function fmtDate(d: string | null) {
  if (!d) return 'Never';
  return new Date(d).toLocaleDateString('en-IN', { year: 'numeric', month: 'short', day: 'numeric' });
}

const FIELDS = ['DESCRIPTION', 'AMOUNT', 'MERCHANT', 'ACCOUNT_TYPE'];
const OPERATORS = ['CONTAINS', 'EQUALS', 'STARTS_WITH', 'GT', 'LT', 'BETWEEN'];
const ACTION_TYPES = ['ASSIGN_CATEGORY', 'MARK_TRANSFER', 'MARK_INVESTMENT', 'MARK_SUBSCRIPTION', 'ADD_TAG'];

// Only ASSIGN_CATEGORY needs actionValue to mean "a category name" specifically -- the other
// action types either need no value (MARK_TRANSFER) or a free-form one (a tag name, or an
// optional override category name for MARK_INVESTMENT) -- see CategoryRule.ActionType's backend
// doc comment.
function actionValueLabel(actionType: string) {
  switch (actionType) {
    case 'ASSIGN_CATEGORY': return 'Category name';
    case 'ADD_TAG': return 'Tag';
    case 'MARK_INVESTMENT': return 'Category name (optional -- defaults to "Investments")';
    default: return null;
  }
}

function RulesTab() {
  const [rules, setRules] = useState<Rule[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const [field, setField] = useState('DESCRIPTION');
  const [operator, setOperator] = useState('CONTAINS');
  const [comparisonValue, setComparisonValue] = useState('');
  const [actionType, setActionType] = useState('ASSIGN_CATEGORY');
  const [actionValue, setActionValue] = useState('');
  const [priority, setPriority] = useState('100');

  function load() {
    setLoading(true);
    rulesApi.list().then(setRules).finally(() => setLoading(false));
  }
  useEffect(load, []);

  async function createRule() {
    if (!comparisonValue.trim()) {
      setError('Comparison value is required.');
      return;
    }
    const needsValueLabel = actionValueLabel(actionType);
    if (actionType === 'ASSIGN_CATEGORY' && !actionValue.trim()) {
      setError('ASSIGN_CATEGORY rules need a category name.');
      return;
    }
    setError(null);
    setSaving(true);
    try {
      await rulesApi.create({
        field, operator, comparisonValue: comparisonValue.trim(), actionType,
        actionValue: needsValueLabel && actionValue.trim() ? actionValue.trim() : undefined,
        priority: priority ? parseInt(priority, 10) : undefined,
      });
      setComparisonValue('');
      setActionValue('');
      load();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not create this rule.');
    } finally {
      setSaving(false);
    }
  }

  async function toggleEnabled(rule: Rule) {
    await rulesApi.update(rule.id, { enabled: !rule.enabled });
    load();
  }

  async function remove(id: string) {
    await rulesApi.remove(id);
    load();
  }

  const valueLabel = actionValueLabel(actionType);

  return (
    <div className="space-y-4">
      <div className="bg-card rounded p-4 shadow space-y-3">
        <p className="text-xs uppercase text-gray-500 font-medium">New rule</p>
        <div className="grid grid-cols-6 gap-2 items-end">
          <div>
            <label className="block text-xs text-gray-500 mb-1">Field</label>
            <select value={field} onChange={(e) => setField(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full">
              {FIELDS.map((f) => <option key={f} value={f}>{f}</option>)}
            </select>
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">Operator</label>
            <select value={operator} onChange={(e) => setOperator(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full">
              {OPERATORS.map((o) => <option key={o} value={o}>{o}</option>)}
            </select>
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">
              {operator === 'BETWEEN' ? 'Value (low,high)' : 'Value'}
            </label>
            <input value={comparisonValue} onChange={(e) => setComparisonValue(e.target.value)}
              placeholder={operator === 'BETWEEN' ? '1000,5000' : 'swiggy'}
              className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" />
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">Action</label>
            <select value={actionType} onChange={(e) => setActionType(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full">
              {ACTION_TYPES.map((a) => <option key={a} value={a}>{a}</option>)}
            </select>
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">{valueLabel ?? 'Action value (n/a)'}</label>
            <input value={actionValue} onChange={(e) => setActionValue(e.target.value)} disabled={!valueLabel}
              className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full disabled:bg-black/5" />
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">Priority</label>
            <input type="number" value={priority} onChange={(e) => setPriority(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" />
          </div>
        </div>
        <button onClick={createRule} disabled={saving} className="bg-primary text-white hover:bg-primary-dark px-4 py-2 rounded text-xs uppercase disabled:opacity-50">
          {saving ? 'Saving…' : 'Add Rule'}
        </button>
        {error && <p className="text-danger text-sm">{error}</p>}
      </div>

      <div className="bg-card rounded shadow overflow-hidden">
        {loading ? (
          <p className="text-sm text-gray-500 p-4">Loading…</p>
        ) : rules.length === 0 ? (
          <p className="text-sm italic text-gray-500 p-4">No rules yet.</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-black/5 text-xs uppercase text-gray-500">
              <tr>
                <th className="text-left px-4 py-2.5">Scope</th>
                <th className="text-left px-4 py-2.5">Condition</th>
                <th className="text-left px-4 py-2.5">Action</th>
                <th className="text-left px-4 py-2.5">Priority</th>
                <th className="text-left px-4 py-2.5" title="How many transactions this rule has actually decided -- see RuleEngineService.recordMatch">Matches</th>
                <th className="text-right px-4 py-2.5">Enabled</th>
                <th className="text-right px-4 py-2.5"></th>
              </tr>
            </thead>
            <tbody>
              {rules.map((r) => (
                <tr key={r.id} className="border-t border-black/5">
                  <td className="px-4 py-2.5">
                    <span className={`px-1.5 py-0.5 rounded text-xs ${r.scope === 'GLOBAL' ? 'bg-black/10 text-gray-600' : 'bg-primary/15 text-primary-dark'}`}>
                      {r.scope}
                    </span>
                  </td>
                  <td className="px-4 py-2.5 text-gray-600">{r.field} {r.operator} "{r.comparisonValue}"</td>
                  <td className="px-4 py-2.5 text-gray-600">{r.actionType}{r.actionValue ? ` → ${r.actionValue}` : ''}</td>
                  <td className="px-4 py-2.5 text-gray-500">{r.priority}</td>
                  <td className="px-4 py-2.5 text-gray-500">
                    {r.matchCount.toLocaleString('en-IN')}
                    <span className="text-gray-400"> · last {fmtDate(r.lastMatchedAt)}</span>
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    {r.scope === 'USER' ? (
                      <input type="checkbox" checked={r.enabled} onChange={() => toggleEnabled(r)} />
                    ) : (
                      <span className="text-xs text-gray-400 italic">System</span>
                    )}
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    {r.scope === 'USER' && (
                      <button onClick={() => remove(r.id)} className="text-gray-400 hover:text-danger"><Trash2 size={14} /></button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

const IDENTIFIER_TYPES = ['UPI_ID', 'ACCOUNT_LAST4', 'NAME_PATTERN'];
const RELATIONSHIP_TYPES = ['FAMILY', 'FRIEND', 'OWN_ACCOUNT', 'OTHER'];

function RelationshipsTab() {
  const [relationships, setRelationships] = useState<Relationship[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const [label, setLabel] = useState('');
  const [relationshipType, setRelationshipType] = useState('FAMILY');
  const [identifierType, setIdentifierType] = useState('UPI_ID');
  const [identifierValue, setIdentifierValue] = useState('');

  const [editing, setEditing] = useState<Relationship | null>(null);
  const [merging, setMerging] = useState<Relationship | null>(null);
  const [viewingTxns, setViewingTxns] = useState<Relationship | null>(null);

  function load() {
    setLoading(true);
    relationshipsApi.list().then(setRelationships).finally(() => setLoading(false));
  }
  useEffect(load, []);

  async function createRelationship() {
    if (!label.trim() || !identifierValue.trim()) {
      setError('Label and at least one identifier value are required.');
      return;
    }
    setError(null);
    setSaving(true);
    try {
      await relationshipsApi.create({
        label: label.trim(),
        relationshipType,
        identifiers: [{ identifierType, identifierValue: identifierValue.trim() }],
      });
      setLabel('');
      setIdentifierValue('');
      load();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not create this relationship.');
    } finally {
      setSaving(false);
    }
  }

  async function remove(id: string) {
    await relationshipsApi.remove(id);
    load();
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-gray-500">
        A known identifier (a UPI ID, the last 4 digits of an account, a recurring name pattern)
        widens the window ReconciliationService uses to match internal transfers -- see
        docs/rule-engine-relationship-engine-eds.md §4.
      </p>
      <div className="bg-card rounded p-4 shadow space-y-3">
        <p className="text-xs uppercase text-gray-500 font-medium">New relationship</p>
        <div className="grid grid-cols-4 gap-2 items-end">
          <div>
            <label className="block text-xs text-gray-500 mb-1">Label</label>
            <input value={label} onChange={(e) => setLabel(e.target.value)} placeholder="My Card Account"
              className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" />
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">Type</label>
            <select value={relationshipType} onChange={(e) => setRelationshipType(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full">
              {RELATIONSHIP_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
            </select>
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">Identifier type</label>
            <select value={identifierType} onChange={(e) => setIdentifierType(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full">
              {IDENTIFIER_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
            </select>
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">Identifier value</label>
            <input value={identifierValue} onChange={(e) => setIdentifierValue(e.target.value)} placeholder="XX4802"
              className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" />
          </div>
        </div>
        <button onClick={createRelationship} disabled={saving} className="bg-primary text-white hover:bg-primary-dark px-4 py-2 rounded text-xs uppercase disabled:opacity-50">
          {saving ? 'Saving…' : 'Add Relationship'}
        </button>
        {error && <p className="text-danger text-sm">{error}</p>}
      </div>

      <div className="bg-card rounded shadow overflow-hidden">
        {loading ? (
          <p className="text-sm text-gray-500 p-4">Loading…</p>
        ) : relationships.length === 0 ? (
          <p className="text-sm italic text-gray-500 p-4">No relationships yet.</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-black/5 text-xs uppercase text-gray-500">
              <tr>
                <th className="text-left px-4 py-2.5">Label</th>
                <th className="text-left px-4 py-2.5">Type</th>
                <th className="text-left px-4 py-2.5">Identifiers</th>
                <th className="text-right px-4 py-2.5"></th>
              </tr>
            </thead>
            <tbody>
              {relationships.map((r) => (
                <tr key={r.id} className="border-t border-black/5">
                  <td className="px-4 py-2.5 font-medium">{r.label}</td>
                  <td className="px-4 py-2.5 text-gray-600">{r.relationshipType}</td>
                  <td className="px-4 py-2.5 text-gray-500">
                    {r.identifiers.map((i) => `${i.identifierType}: ${i.identifierValue}`).join(', ')}
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    <div className="flex items-center justify-end gap-1">
                      <button onClick={() => setViewingTxns(r)} title="View matched transactions" className="text-gray-400 hover:text-ink p-1"><ListChecks size={14} /></button>
                      <button onClick={() => setEditing(r)} title="Edit" className="text-gray-400 hover:text-ink p-1"><Pencil size={14} /></button>
                      {relationships.length > 1 && (
                        <button onClick={() => setMerging(r)} title="Merge another relationship into this one" className="text-gray-400 hover:text-ink p-1"><GitMerge size={14} /></button>
                      )}
                      <button onClick={() => remove(r.id)} title="Delete" className="text-gray-400 hover:text-danger p-1"><Trash2 size={14} /></button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {editing && (
        <EditRelationshipModal relationship={editing} onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); load(); }} />
      )}
      {merging && (
        <MergeRelationshipModal relationship={merging} allRelationships={relationships} onClose={() => setMerging(null)}
          onMerged={() => { setMerging(null); load(); }} />
      )}
      {viewingTxns && (
        <RelationshipTransactionsModal relationship={viewingTxns} onClose={() => setViewingTxns(null)} />
      )}
    </div>
  );
}

function ModalShell({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={onClose} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <div className="bg-card border border-black/10 rounded-xl shadow-lg w-full max-w-lg max-h-[80vh] overflow-y-auto p-5 pointer-events-auto">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold text-ink text-sm">{title}</h3>
            <button type="button" onClick={onClose} className="text-gray-400 hover:text-ink"><X size={18} /></button>
          </div>
          {children}
        </div>
      </div>
    </>
  );
}

function EditRelationshipModal({
  relationship, onClose, onSaved,
}: { relationship: Relationship; onClose: () => void; onSaved: () => void }) {
  const [label, setLabel] = useState(relationship.label);
  const [relationshipType, setRelationshipType] = useState(relationship.relationshipType);
  const [identifiers, setIdentifiers] = useState(
    relationship.identifiers.map((i) => ({ identifierType: i.identifierType, identifierValue: i.identifierValue })));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function updateIdentifier(index: number, field: 'identifierType' | 'identifierValue', value: string) {
    setIdentifiers((prev) => prev.map((id, i) => (i === index ? { ...id, [field]: value } : id)));
  }
  function removeIdentifier(index: number) {
    setIdentifiers((prev) => prev.filter((_, i) => i !== index));
  }
  function addIdentifier() {
    setIdentifiers((prev) => [...prev, { identifierType: 'UPI_ID', identifierValue: '' }]);
  }

  async function save() {
    if (!label.trim()) { setError('Label can\'t be blank.'); return; }
    setSaving(true);
    setError(null);
    try {
      await relationshipsApi.update(relationship.id, {
        label: label.trim(),
        relationshipType,
        identifiers: identifiers.filter((i) => i.identifierValue.trim()),
      });
      onSaved();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not save these changes.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <ModalShell title={`Edit — ${relationship.label}`} onClose={onClose}>
      <div className="space-y-3 text-sm">
        <div>
          <label className="block text-xs text-gray-500 mb-1">Label</label>
          <input value={label} onChange={(e) => setLabel(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" />
        </div>
        <div>
          <label className="block text-xs text-gray-500 mb-1">Type</label>
          {/* Bug fix: relationshipType here is seeded from relationship.relationshipType
              (Relationship['relationshipType'], a 4-literal union), unlike the plain useState('FAMILY')
              in the create-relationship form above -- so unlike that one, a raw string from
              e.target.value doesn't structurally match its setter. RELATIONSHIP_TYPES only ever
              populates these <option>s with one of those 4 literals, so the cast is safe. */}
          <select value={relationshipType} onChange={(e) => setRelationshipType(e.target.value as Relationship['relationshipType'])} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full">
            {RELATIONSHIP_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </div>
        <div>
          <label className="block text-xs text-gray-500 mb-1">Identifiers</label>
          <div className="space-y-1.5">
            {identifiers.map((id, i) => (
              <div key={i} className="flex gap-1.5">
                <select value={id.identifierType} onChange={(e) => updateIdentifier(i, 'identifierType', e.target.value)}
                  className="bg-card text-ink border rounded px-2 py-1 text-xs w-1/3">
                  {IDENTIFIER_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                </select>
                <input value={id.identifierValue} onChange={(e) => updateIdentifier(i, 'identifierValue', e.target.value)}
                  className="bg-card text-ink border rounded px-2 py-1 text-xs flex-1" />
                <button onClick={() => removeIdentifier(i)} className="text-gray-400 hover:text-danger px-1"><Trash2 size={13} /></button>
              </div>
            ))}
          </div>
          <button onClick={addIdentifier} className="text-xs text-primary mt-1.5">+ Add identifier</button>
        </div>
        {error && <p className="text-danger text-xs">{error}</p>}
        <div className="flex justify-end gap-2 pt-2">
          <button onClick={onClose} className="text-xs px-3 py-1.5 rounded border border-black/10 text-gray-600">Cancel</button>
          <button onClick={save} disabled={saving} className="bg-primary text-white text-xs px-3 py-1.5 rounded disabled:opacity-50">
            {saving ? 'Saving…' : 'Save changes'}
          </button>
        </div>
      </div>
    </ModalShell>
  );
}

function MergeRelationshipModal({
  relationship, allRelationships, onClose, onMerged,
}: { relationship: Relationship; allRelationships: Relationship[]; onClose: () => void; onMerged: () => void }) {
  const candidates = allRelationships.filter((r) => r.id !== relationship.id);
  const [mergeFromId, setMergeFromId] = useState(candidates[0]?.id ?? '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function merge() {
    if (!mergeFromId) return;
    setSaving(true);
    setError(null);
    try {
      await relationshipsApi.merge(relationship.id, mergeFromId);
      onMerged();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not merge these relationships.');
    } finally {
      setSaving(false);
    }
  }

  const mergeFrom = candidates.find((c) => c.id === mergeFromId);

  return (
    <ModalShell title={`Merge into — ${relationship.label}`} onClose={onClose}>
      <div className="space-y-3 text-sm">
        <p className="text-xs text-gray-500">
          Every identifier on the relationship you pick below moves onto <strong>{relationship.label}</strong>,
          and that relationship is deleted. This can't be undone.
        </p>
        <div>
          <label className="block text-xs text-gray-500 mb-1">Merge this relationship in</label>
          <select value={mergeFromId} onChange={(e) => setMergeFromId(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full">
            {candidates.map((c) => <option key={c.id} value={c.id}>{c.label} ({c.relationshipType})</option>)}
          </select>
        </div>
        {mergeFrom && (
          <p className="text-xs text-gray-500">
            {mergeFrom.identifiers.length} identifier{mergeFrom.identifiers.length === 1 ? '' : 's'} will move over:{' '}
            {mergeFrom.identifiers.map((i) => i.identifierValue).join(', ') || 'none'}
          </p>
        )}
        {error && <p className="text-danger text-xs">{error}</p>}
        <div className="flex justify-end gap-2 pt-2">
          <button onClick={onClose} className="text-xs px-3 py-1.5 rounded border border-black/10 text-gray-600">Cancel</button>
          <button onClick={merge} disabled={saving || !mergeFromId} className="bg-primary text-white text-xs px-3 py-1.5 rounded disabled:opacity-50">
            {saving ? 'Merging…' : 'Merge'}
          </button>
        </div>
      </div>
    </ModalShell>
  );
}

function RelationshipTransactionsModal({ relationship, onClose }: { relationship: Relationship; onClose: () => void }) {
  const [transactions, setTransactions] = useState<Transaction[] | null>(null);

  useEffect(() => {
    relationshipsApi.transactions(relationship.id).then(setTransactions);
  }, [relationship.id]);

  return (
    <ModalShell title={`Matched transactions — ${relationship.label}`} onClose={onClose}>
      {transactions === null ? (
        <p className="text-sm text-gray-500">Loading…</p>
      ) : transactions.length === 0 ? (
        <p className="text-sm italic text-gray-500">No transactions match this relationship's identifiers yet.</p>
      ) : (
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase text-gray-500 border-b border-black/10">
              <th className="py-1.5">Date</th><th className="py-1.5">Description</th>
              <th className="py-1.5">Category</th><th className="py-1.5 text-right">Amount</th>
            </tr>
          </thead>
          <tbody>
            {transactions.map((t) => (
              <tr key={t.id} className="border-b border-black/5">
                <td className="py-1.5">{t.date}</td>
                <td className="py-1.5">{t.description || t.merchant}</td>
                <td className="py-1.5 text-gray-500">{t.categoryName}</td>
                <td className={`py-1.5 text-right font-medium ${t.type === 'INCOME' ? 'text-success' : 'text-danger'}`}>
                  {t.type === 'INCOME' ? '+' : '-'}₹{Math.round(Math.abs(t.amount)).toLocaleString('en-IN')}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </ModalShell>
  );
}

export default function Rules() {
  const [tab, setTab] = useState<'rules' | 'relationships'>('rules');

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <SlidersHorizontal size={20} className="text-primary" />
        <h1 className="text-lg font-semibold">Rules &amp; Relationships</h1>
      </div>

      <div className="flex gap-1 border-b border-black/10">
        <button
          onClick={() => setTab('rules')}
          className={`px-4 py-2 text-sm font-medium border-b-2 -mb-px ${tab === 'rules' ? 'border-primary text-primary' : 'border-transparent text-gray-500'}`}
        >
          Category Rules
        </button>
        <button
          onClick={() => setTab('relationships')}
          className={`px-4 py-2 text-sm font-medium border-b-2 -mb-px flex items-center gap-1.5 ${tab === 'relationships' ? 'border-primary text-primary' : 'border-transparent text-gray-500'}`}
        >
          <Users size={14} /> Relationships
        </button>
      </div>

      {tab === 'rules' ? <RulesTab /> : <RelationshipsTab />}
    </div>
  );
}
