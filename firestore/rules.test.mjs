// اختبارات قواعد Firestore (محاكي). تشغيل: cd firestore && npm i && npm test
// لم تُشغَّل في بيئة التسليم (لا شبكة لتثبيت الحزم ولا محاكي). راجع التقرير.
import { test, before, after, beforeEach } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, setDoc, updateDoc, deleteDoc, writeBatch, increment } from 'firebase/firestore';

let env, db, anon;
before(async () => {
  env = await initializeTestEnvironment({ projectId: 'mgtik-rules', firestore: { rules: readFileSync('firestore.rules', 'utf8') } });
  db = env.authenticatedContext('admin', { firebase: { sign_in_provider: 'password' } }).firestore();
  anon = env.authenticatedContext('guest', { firebase: { sign_in_provider: 'anonymous' } }).firestore();
});
after(() => env.cleanup());
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (c) => {
    const d = c.firestore();
    await setDoc(doc(d, 'profile_categories/C1'), { name_ar: 'يومية', status: 'active', profile_count: 0 });
    await setDoc(doc(d, 'profile_categories/C2'), { name_ar: 'أسبوعية', status: 'active', profile_count: 0 });
    await setDoc(doc(d, 'profile_categories/CD'), { name_ar: 'معطلة', status: 'disabled', profile_count: 0 });
    await setDoc(doc(d, 'owners/A'), { name: 'وكيل أ', status: 'ACTIVE' });
    await setDoc(doc(d, 'owners/Z'), { name: 'قديم', status: 'ARCHIVED' });
  });
});
const P = (o = {}) => ({ linked_profile_name: 'g1', status: 'active', price_minor: 500, in_use: false, category_id: null, ...o });
const seedProfile = (id, extra = {}, catCount) => env.withSecurityRulesDisabled(async (c) => {
  const d = c.firestore();
  await setDoc(doc(d, `profiles/${id}`), P(extra));
  if (extra.category_id) await setDoc(doc(d, `profile_categories/${extra.category_id}`), { name_ar: 'x', status: 'active', profile_count: catCount ?? 1 });
});

test('guest (anonymous) cannot read or write', async () => {
  await assertFails(setDoc(doc(anon, 'profiles/p'), P()));
});
test('create profile: valid ok, zero price on active rejected, bad status rejected', async () => {
  await assertSucceeds(setDoc(doc(db, 'profiles/p1'), P()));
  await assertFails(setDoc(doc(db, 'profiles/p2'), P({ price_minor: 0 })));
  await assertFails(setDoc(doc(db, 'profiles/p3'), P({ status: 'weird' })));
  await assertFails(setDoc(doc(db, 'profiles/p4'), P({ price_minor: -5 })));
});
test('create profile in category must bump counter atomically', async () => {
  const b = writeBatch(db);
  b.set(doc(db, 'profiles/p1'), P({ category_id: 'C1' }));
  b.update(doc(db, 'profile_categories/C1'), { profile_count: increment(1) });
  await assertSucceeds(b.commit());
  await assertFails(setDoc(doc(db, 'profiles/p2'), P({ category_id: 'C1' }))); // بلا عدّاد
});
test('cannot assign to disabled category even with counter bump', async () => {
  const b = writeBatch(db);
  b.set(doc(db, 'profiles/p1'), P({ category_id: 'CD' }));
  b.update(doc(db, 'profile_categories/CD'), { profile_count: increment(1) });
  await assertFails(b.commit());
});
test('move profile between categories needs both counters', async () => {
  await seedProfile('p1', { category_id: 'C1' }, 1);
  const bad = writeBatch(db);
  bad.update(doc(db, 'profiles/p1'), { category_id: 'C2' });
  await assertFails(bad.commit());
  const ok = writeBatch(db);
  ok.update(doc(db, 'profiles/p1'), { category_id: 'C2' });
  ok.update(doc(db, 'profile_categories/C1'), { profile_count: increment(-1) });
  ok.update(doc(db, 'profile_categories/C2'), { profile_count: increment(1) });
  await assertSucceeds(ok.commit());
});
test('delete profile: blocked when in_use, allowed when not (with counter release)', async () => {
  await seedProfile('used', { in_use: true });
  await assertFails(deleteDoc(doc(db, 'profiles/used')));
  await seedProfile('free', { category_id: 'C1' }, 1);
  const noRelease = writeBatch(db); noRelease.delete(doc(db, 'profiles/free'));
  await assertFails(noRelease.commit());
  const b = writeBatch(db);
  b.delete(doc(db, 'profiles/free'));
  b.update(doc(db, 'profile_categories/C1'), { profile_count: increment(-1) });
  await assertSucceeds(b.commit());
});
test('in_use can go false->true but never back', async () => {
  await seedProfile('p1');
  await assertSucceeds(updateDoc(doc(db, 'profiles/p1'), { in_use: true }));
  await assertFails(updateDoc(doc(db, 'profiles/p1'), { in_use: false }));
});
test('activating a zero-price profile is rejected', async () => {
  await seedProfile('p1', { status: 'disabled', price_minor: 0 });
  await assertFails(updateDoc(doc(db, 'profiles/p1'), { status: 'active' }));
  await assertSucceeds(updateDoc(doc(db, 'profiles/p1'), { price_minor: 300, status: 'active' }));
});
test('delete category: only with zero counter', async () => {
  await env.withSecurityRulesDisabled(async (c) => setDoc(doc(c.firestore(), 'profile_categories/F'), { name_ar: 'f', status: 'active', profile_count: 2 }));
  await assertFails(deleteDoc(doc(db, 'profile_categories/F')));
  await assertSucceeds(deleteDoc(doc(db, 'profile_categories/C1')));
});
test('category counter cannot be set arbitrarily', async () => {
  await assertFails(updateDoc(doc(db, 'profile_categories/C1'), { profile_count: 7 }));
  await assertFails(updateDoc(doc(db, 'profile_categories/C1'), { profile_count: -1 }));
});
test('special price: valid, deterministic id, invalid price/owner rejected', async () => {
  await seedProfile('P1');
  const ok = { cloud_profile_id: 'P1', owner_id: 'A', retail_price_minor: 400, active: true };
  await assertSucceeds(setDoc(doc(db, 'pricing_rules/P1__A'), ok));
  await assertFails(setDoc(doc(db, 'pricing_rules/random-id'), ok));              // معرّف غير ثابت = تكرار محتمل
  await assertFails(setDoc(doc(db, 'pricing_rules/P1__A'), { ...ok, retail_price_minor: 0 }));
  await assertFails(setDoc(doc(db, 'pricing_rules/P1__Z'), { ...ok, owner_id: 'Z' })); // وكيل مؤرشف
  await assertFails(setDoc(doc(db, 'pricing_rules/P1__Q'), { ...ok, owner_id: 'Q' })); // وكيل غير موجود
  await assertFails(setDoc(doc(db, 'pricing_rules/legacy'), { cloud_profile_id: 'P1', retail_price_minor: 5 })); // قاعدة عامة قديمة
});
test('special price can be disabled even if profile later archived', async () => {
  await seedProfile('P1');
  await assertSucceeds(setDoc(doc(db, 'pricing_rules/P1__A'), { cloud_profile_id: 'P1', owner_id: 'A', retail_price_minor: 400, active: true }));
  await env.withSecurityRulesDisabled(async (c) => updateDoc(doc(c.firestore(), 'profiles/P1'), { status: 'archived' }));
  await assertSucceeds(updateDoc(doc(db, 'pricing_rules/P1__A'), { active: false }));
});
test('batch create requires profile marked in_use in the same write; price snapshot immutable', async () => {
  await seedProfile('P1');
  const batch = { cloud_profile_id: 'P1', price_per_voucher_minor: 400, owner_id: 'A', status: 'RUNNING' };
  await assertFails(setDoc(doc(db, 'batches/b0'), batch));
  const b = writeBatch(db);
  b.set(doc(db, 'batches/b1'), batch);
  b.update(doc(db, 'profiles/P1'), { in_use: true });
  await assertSucceeds(b.commit());
  await assertSucceeds(updateDoc(doc(db, 'batches/b1'), { status: 'COMPLETED' }));
  await assertFails(updateDoc(doc(db, 'batches/b1'), { price_per_voucher_minor: 1 }));
});
test('subscription create for an existing profile must mark it in_use', async () => {
  await seedProfile('P1');
  await assertFails(setDoc(doc(db, 'subscriptions/s0'), { profile_id: 'P1' }));
  const b = writeBatch(db);
  b.set(doc(db, 'subscriptions/s1'), { profile_id: 'P1' });
  b.update(doc(db, 'profiles/P1'), { in_use: true });
  await assertSucceeds(b.commit());
});

const INV = (o = {}) => ({ status: 'DUE', subtotalMinor: 2000, totalMinor: 2000, amountPaidMinor: 0, amountDueMinor: 2000, ...o });
test('invoice: consistent amounts pass (both status casings), contradictions fail', async () => {
  await assertSucceeds(setDoc(doc(db, 'invoices/i1'), INV()));
  await assertSucceeds(setDoc(doc(db, 'invoices/i2'), INV({ status: 'partially_paid', amountPaidMinor: 500, amountDueMinor: 1500 })));
  await assertSucceeds(setDoc(doc(db, 'invoices/i3'), INV({ status: 'paid', amountPaidMinor: 2000, amountDueMinor: 0 })));
  await assertFails(setDoc(doc(db, 'invoices/i4'), INV({ amountPaidMinor: 3000, amountDueMinor: -1000 })));   // مدفوع أكثر من الإجمالي
  await assertFails(setDoc(doc(db, 'invoices/i5'), INV({ amountPaidMinor: 500, amountDueMinor: 999 })));      // متبقٍ لا يطابق
  await assertFails(setDoc(doc(db, 'invoices/i6'), INV({ subtotalMinor: 1000 })));                           // خصم سالب
  await assertFails(setDoc(doc(db, 'invoices/i7'), INV({ status: 'paid', amountDueMinor: 2000 })));         // مدفوعة وعليها متبقٍ
  await assertFails(setDoc(doc(db, 'invoices/i8'), INV({ status: 'weird' })));
});
test('canceled invoice may zero its balance; invoices cannot be deleted', async () => {
  await assertSucceeds(setDoc(doc(db, 'invoices/c1'), INV({ status: 'canceled', amountDueMinor: 0 })));
  await assertFails(deleteDoc(doc(db, 'invoices/c1')));
});
test('payments: amount and invoice link immutable, no delete', async () => {
  await assertSucceeds(setDoc(doc(db, 'payments/p1'), { invoiceId: 'i1', amountMinor: 500, status: 'active' }));
  await assertSucceeds(updateDoc(doc(db, 'payments/p1'), { status: 'void' }));
  await assertFails(updateDoc(doc(db, 'payments/p1'), { amountMinor: 1 }));
  await assertFails(updateDoc(doc(db, 'payments/p1'), { invoiceId: 'other' }));
  await assertFails(deleteDoc(doc(db, 'payments/p1')));
});

const RC = (o = {}) => ({ host: '192.168.88.1', port: 80, api_port: 8728, username: 'admin', password: 'x', use_ssl: false, connection_type: 'AUTO', ...o });
test('settings/router_config: admin read+write valid; invalid shape, guest and delete are rejected', async () => {
  await assertSucceeds(setDoc(doc(db, 'settings/router_config'), RC()));
  await assertSucceeds(setDoc(doc(db, 'settings/router_config'), RC({ host: 'router.local', connection_type: 'API' })));
  await assertFails(setDoc(doc(db, 'settings/router_config'), RC({ host: '' })));
  await assertFails(setDoc(doc(db, 'settings/router_config'), RC({ port: 70000 })));
  await assertFails(setDoc(doc(db, 'settings/router_config'), RC({ connection_type: 'WEIRD' })));
  await assertFails(setDoc(doc(anon, 'settings/router_config'), RC()));
  await assertFails(deleteDoc(doc(db, 'settings/router_config')));
});
test('settings/company_info: admin can write, guest cannot', async () => {
  await assertSucceeds(setDoc(doc(db, 'settings/company_info'), { company_name: 'x' }));
  await assertFails(setDoc(doc(anon, 'settings/company_info'), { company_name: 'x' }));
});
const CI = (o = {}) => ({ company_name: 'شبكة', network_name: 'n', currency_symbol: '₪', email: 'a@b.co', website: 'https://x.io', ...o });
test('settings/company_info: اسم الشركة إلزامي، والعملة 1..6 أحرف، والأنواع نصوص بسقف طول', async () => {
  await assertSucceeds(setDoc(doc(db, 'settings/company_info'), CI()));
  await assertSucceeds(setDoc(doc(db, 'settings/company_info'), CI({ footer_notes: 'x'.repeat(1000) })));
  await assertFails(setDoc(doc(db, 'settings/company_info'), CI({ company_name: '' })));
  await assertFails(setDoc(doc(db, 'settings/company_info'), CI({ company_name: 5 })));
  await assertFails(setDoc(doc(db, 'settings/company_info'), CI({ currency_symbol: '' })));
  await assertFails(setDoc(doc(db, 'settings/company_info'), CI({ currency_symbol: 'TOOLONG' })));
  await assertFails(setDoc(doc(db, 'settings/company_info'), CI({ email: 123 })));
  await assertFails(setDoc(doc(db, 'settings/company_info'), CI({ footer_notes: 'x'.repeat(1001) })));
  // merge (كما يحفظ التطبيق): الحقول غير المعروفة تبقى، والنتيجة المدموجة تخضع للتحقق نفسه
  await assertSucceeds(setDoc(doc(db, 'settings/company_info'), { extra_field: 1 }, { merge: true }));
  await assertFails(setDoc(doc(db, 'settings/company_info'), { currency_symbol: '' }, { merge: true }));
  await assertFails(deleteDoc(doc(db, 'settings/company_info')));
});
const UM = (o = {}) => ({ mode: 'AUTO', customer: 'admin', radius_address: '127.0.0.1', radius_secret: '', nas_name: 'mgtik-hotspot', ...o });
test('settings/userman_config: الوضع من AUTO|V6|V7 والحقول الأساسية غير فارغة', async () => {
  await assertSucceeds(setDoc(doc(db, 'settings/userman_config'), UM()));
  await assertSucceeds(setDoc(doc(db, 'settings/userman_config'), UM({ mode: 'V7', radius_secret: 's3cret' })));
  await assertFails(setDoc(doc(db, 'settings/userman_config'), UM({ mode: 'V5' })));
  await assertFails(setDoc(doc(db, 'settings/userman_config'), UM({ customer: '' })));
  await assertFails(setDoc(doc(db, 'settings/userman_config'), UM({ radius_address: '' })));
  await assertFails(setDoc(doc(db, 'settings/userman_config'), UM({ nas_name: '' })));
  await assertFails(setDoc(doc(db, 'settings/userman_config'), UM({ radius_secret: 5 })));
  await assertFails(setDoc(doc(anon, 'settings/userman_config'), UM()));
});

// ───────────── التصنيف النظامي للفئات ─────────────
test('فئة جديدة: system_class إلزامي وقيمه normal|subscription فقط', async () => {
  await assertFails(setDoc(doc(db, 'profile_categories/N0'), { name_ar: 'بلا تصنيف', status: 'active', profile_count: 0 }));
  await assertFails(setDoc(doc(db, 'profile_categories/N1'), { name_ar: 'قيمة خاطئة', status: 'active', profile_count: 0, system_class: 'vip' }));
  await assertSucceeds(setDoc(doc(db, 'profile_categories/N2'), { name_ar: 'عادية', status: 'active', profile_count: 0, system_class: 'normal' }));
});

test('التصنيف النظامي ثابت بعد تعيينه، وتُعيَّن الفئة القديمة مرة واحدة', async () => {
  await assertSucceeds(updateDoc(doc(db, 'profile_categories/C1'), { system_class: 'subscription' })); // قديمة بلا تصنيف
  await assertFails(updateDoc(doc(db, 'profile_categories/C1'), { system_class: 'normal' }));
  await assertSucceeds(updateDoc(doc(db, 'profile_categories/C1'), { name_ar: 'يومية 2' }));
});

// ───────────── سجل التعديلات التجارية للفئة (ثابت) ─────────────
import { serverTimestamp, getDoc } from 'firebase/firestore';
const H = (o = {}) => ({ at: serverTimestamp(), action: 'category_updated', changes: [{ field: 'retail_price_minor', old: '1000', new: '1200' }], ...o });

test('سجل الفئة: يُنشأ بختم الخادم فقط ويقرؤه المدير ولا يقرؤه الضيف', async () => {
  await assertSucceeds(setDoc(doc(db, 'profile_categories/C1/history/h1'), H()));
  await assertSucceeds(getDoc(doc(db, 'profile_categories/C1/history/h1')));
  await assertFails(getDoc(doc(anon, 'profile_categories/C1/history/h1')));
  await assertFails(setDoc(doc(anon, 'profile_categories/C1/history/h2'), H()));
  await assertFails(setDoc(doc(db, 'profile_categories/C1/history/h3'), H({ at: new Date() })));      // لا تاريخ من العميل
  await assertFails(setDoc(doc(db, 'profile_categories/C1/history/h4'), H({ changes: 'x' })));         // changes قائمة
});

test('سجل الفئة: لا تعديل ولا حذف بعد الكتابة', async () => {
  await assertSucceeds(setDoc(doc(db, 'profile_categories/C1/history/h1'), H()));
  await assertFails(updateDoc(doc(db, 'profile_categories/C1/history/h1'), { action: 'x' }));
  await assertFails(deleteDoc(doc(db, 'profile_categories/C1/history/h1')));
});
