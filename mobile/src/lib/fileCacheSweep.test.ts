import { Directory, File, Paths } from 'expo-file-system';
import { sweepFileCache } from './fileCacheSweep';

const ONE_HOUR_MS = 60 * 60 * 1000;

/**
 * D2 (Track D security cleanup). Unique filenames per test, not a reset between them: the jest
 * mock for expo-file-system keeps one global in-memory store for the whole file, with no
 * automatic reset between `it()` blocks, and this sweep only ever targets the single top-level
 * cache directory (no test-scoped subdirectory to isolate into) -- so two tests writing the same
 * name would collide, but a unique name per test keeps each assertion valid regardless of what
 * earlier tests left behind.
 */
function tempFile(name: string): File {
  const file = new File(Paths.cache, name);
  file.create();
  file.write('x');
  return file;
}

describe('sweepFileCache', () => {
  it('deletes a file older than the max age', () => {
    const file = tempFile('old-statement.csv');
    const writtenAt = file.lastModified as number;

    sweepFileCache(writtenAt + ONE_HOUR_MS + 1);

    expect(file.exists).toBe(false);
  });

  it('leaves a file younger than the max age alone', () => {
    const file = tempFile('fresh-statement.csv');
    const writtenAt = file.lastModified as number;

    sweepFileCache(writtenAt + 1);

    expect(file.exists).toBe(true);
    file.delete(); // Doesn't leak into other tests' Directory.list() results.
  });

  it('sweeps every stale file, not just the first', () => {
    const a = tempFile('stale-a.csv');
    const b = tempFile('stale-b.csv');
    const writtenAt = Math.max(a.lastModified as number, b.lastModified as number);

    sweepFileCache(writtenAt + ONE_HOUR_MS + 1);

    expect(a.exists).toBe(false);
    expect(b.exists).toBe(false);
  });

  it('does not crash and does not delete a subdirectory found at the top level', () => {
    const dir = new Directory(Paths.cache, 'some-other-librarys-cache');
    dir.create({ idempotent: true });

    expect(() => sweepFileCache(Date.now() + 10 * ONE_HOUR_MS)).not.toThrow();
    expect(dir.exists).toBe(true);
  });

  // Exercises the real default (Date.now()), not an injected value -- everything else in this
  // file pins `now` for determinism, but production code calls sweepFileCache() with no argument.
  it('does not throw when called with the real clock', () => {
    expect(() => sweepFileCache()).not.toThrow();
  });
});
