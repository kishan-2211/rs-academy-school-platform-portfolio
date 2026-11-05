import test from 'node:test';
import assert from 'node:assert/strict';
import { isLocale, resolveLocale, languageCookie, readSessionLanguage, interpolate, localizedContent, LANGUAGES } from './locale.ts';
import { hindi } from './messages.ts';
import { translateCode } from './codes.ts';

test('explicit session > saved account > local cookie > English', () => {
  assert.equal(resolveLocale('hi', 'en', 'en'), 'hi');
  assert.equal(resolveLocale(null, 'hi', 'en'), 'hi');
  assert.equal(resolveLocale(null, null, 'hi'), 'hi');
  assert.equal(resolveLocale(undefined, undefined, undefined), 'en');
  assert.equal(resolveLocale('invalid', 'en', 'hi'), 'en');
  assert.equal(resolveLocale(null, null, 'hi; injected=true'), 'en');
});
test('only supported language identifiers are accepted and Hindi is visibly identifiable', () => {
  assert.ok(isLocale('hi'));
  for (const value of ['HI', 'hi-IN', '', '<script>', null, {}, 1]) assert.equal(isLocale(value), false);
  assert.equal(LANGUAGES.find(x => x.value === 'hi').label, 'हिंदी');
});
test('cookie is language-only and storage denial is recoverable', () => {
  assert.equal(languageCookie('hi', false), 'educore_language=hi; Path=/; Max-Age=31536000; SameSite=Lax');
  assert.ok(languageCookie('en', true).endsWith('; Secure'));
  assert.equal(readSessionLanguage({ getItem: () => { throw new Error('denied'); } }), null);
  assert.equal(readSessionLanguage({ getItem: () => 'hi' }), 'hi');
  assert.equal(readSessionLanguage(() => { throw new Error('storage getter denied'); }), null);
});
test('authored bilingual content prefers the selected language without translating user data', () => {
  assert.deepEqual(localizedContent('hi', 'School notice RS-009', 'स्कूल सूचना RS-009'), {
    primary: 'स्कूल सूचना RS-009', primaryLanguage: 'hi', secondary: 'School notice RS-009', secondaryLanguage: 'en',
  });
  assert.equal(localizedContent('hi', 'Untranslated author content', null).primary, 'Untranslated author content');
  assert.equal(localizedContent('hi', 'Untranslated author content', '  ').primaryLanguage, 'en');
  assert.equal(localizedContent('en', null, 'लेखक की सूचना').primaryLanguage, 'hi');
  assert.equal(localizedContent('hi', null, null).primary, '');
});
test('interpolation preserves names, IDs, amounts and user-supplied strings literally', () => {
  assert.equal(interpolate('{name}: {amount} / {id}', {name: 'Synthetic छात्र', amount:'₹1,250.00', id:'RS-009'}), 'Synthetic छात्र: ₹1,250.00 / RS-009');
  assert.equal(interpolate('{name}', { name: '$& <b>नाम</b>' }), '$& <b>नाम</b>');
});
test('catalog placeholders match and reviewed translations are nonempty', () => {
  for (const [domain, messages] of Object.entries(hindi)) {
    for (const [english, translated] of Object.entries(messages)) {
      assert.ok(translated.trim(), `${domain}: ${english}`);
      assert.deepEqual([...english.matchAll(/\{\w+\}/g)].map(x=>x[0]).sort(), [...translated.matchAll(/\{\w+\}/g)].map(x=>x[0]).sort(), `${domain}: ${english}`);
    }
  }
});
test('only controlled status codes are translated; unknown class/ID codes preserve caller formatting', () => {
  assert.equal(translateCode('hi', 'PRESENT', x => x), 'उपस्थित');
  assert.equal(translateCode('en', 'PRESENT', x => x), 'Present');
  assert.equal(translateCode('hi', 'CLASS_X', x => x), 'CLASS_X');
  assert.equal(translateCode('hi', 'RS-009', x => x), 'RS-009');
});
