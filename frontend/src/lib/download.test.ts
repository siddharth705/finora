import { describe, expect, it } from 'vitest';
import { csvCell, toCsv } from './download';

describe('csvCell', () => {
  it('quotes every cell and doubles embedded quotes', () => {
    expect(csvCell('Dining')).toBe('"Dining"');
    expect(csvCell('He said "hi"')).toBe('"He said ""hi"""');
    expect(csvCell('a,b')).toBe('"a,b"');
  });

  // The defect this exists for: quoting handles CSV PARSING, and does nothing about spreadsheet
  // FORMULA interpretation. Excel, LibreOffice and Sheets all evaluate a cell beginning with one
  // of these on open, quoted or not -- and category names are user-controlled, created from any
  // string via resolveOrCreateCategory with no character-class validation anywhere in the DTO
  // layer. The same names flow into admin-facing platform analytics, so the payload can reach
  // someone other than whoever created it.
  it.each(['=HYPERLINK("http://attacker/","click")', '+1+1', '@SUM(A1)', '\tleading tab', '\rleading cr'])(
    'neutralises %j so a spreadsheet treats it as text',
    (payload) => {
      // Both guards apply together: the leading quote defuses the formula, and any quotes inside
      // the payload are still doubled for the CSV parser.
      expect(csvCell(payload)).toBe(`"'${payload.replace(/"/g, '""')}"`);
    }
  );

  // Guarding by prefix alone would catch every negative amount, since '-' is on the dangerous
  // list -- turning each one into text a spreadsheet refuses to sum, which breaks the export for
  // the reason people asked for it. A value that parses as a finite number cannot be a formula.
  it('leaves genuine numbers alone, including negatives', () => {
    expect(csvCell(-500)).toBe('"-500"');
    expect(csvCell('-500.25')).toBe('"-500.25"');
    expect(csvCell(1234.5)).toBe('"1234.5"');
  });

  it('still guards a formula that merely starts like a number', () => {
    expect(csvCell('-1+1')).toBe(`"'-1+1"`);
  });

  // Leading whitespace before a formula-triggering character must not bypass the guard: a
  // user-controlled category name like ' =cmd|...' would otherwise sail through untouched.
  it('guards a formula-triggering character preceded by leading whitespace', () => {
    expect(csvCell(' =cmd|\'/c calc\'!A0')).toBe(`"' =cmd|'/c calc'!A0"`);
  });
});

describe('toCsv', () => {
  it('joins rows with every cell escaped', () => {
    expect(toCsv([['Category', 'Amount'], ['=cmd', -5]]))
      .toBe('\uFEFF"Category","Amount"\n"\'=cmd","-5"');
  });

  /**
   * Bug 45. Without a leading UTF-8 byte order mark, Excel (particularly on Windows) guesses the
   * file's encoding from its bytes alone and defaults to the system codepage instead of UTF-8 --
   * any non-ASCII character (₹, or an accented/non-Latin merchant or category name, both genuinely
   * user-controlled) renders as mojibake the moment the file is opened.
   */
  it('prefixes the output with a UTF-8 byte order mark', () => {
    const csv = toCsv([['Category', 'Amount'], ['Café', 100]]);
    expect(csv.charCodeAt(0)).toBe(0xfeff);
    expect(csv).toBe('\uFEFF"Category","Amount"\n"Café","100"');
  });
});
