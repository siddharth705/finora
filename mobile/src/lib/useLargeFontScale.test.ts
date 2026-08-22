import { renderHook } from '@testing-library/react-native';
import { Dimensions } from 'react-native';
import { useLargeFontScale } from './useLargeFontScale';

// useWindowDimensions reads its initial (and only, for these tests) value from Dimensions.get on
// mount -- spying there, rather than re-mocking the whole 'react-native' module, avoids re-running
// the module's own native TurboModule getters (which blow up under jest-expo when the module
// object is spread rather than used as-is).
const getSpy = jest.spyOn(Dimensions, 'get');

function setFontScale(fontScale: number) {
  getSpy.mockReturnValue({ width: 390, height: 844, scale: 2, fontScale });
}

afterEach(() => getSpy.mockReset());

describe('useLargeFontScale', () => {
  it('is false at the default system font scale', () => {
    setFontScale(1);
    const { result } = renderHook(() => useLargeFontScale());
    expect(result.current).toBe(false);
  });

  it('is false for scales below the large-text threshold', () => {
    setFontScale(1.15);
    const { result } = renderHook(() => useLargeFontScale());
    expect(result.current).toBe(false);
  });

  it('is true at the large-text threshold', () => {
    setFontScale(1.3);
    const { result } = renderHook(() => useLargeFontScale());
    expect(result.current).toBe(true);
  });

  it('is true at accessibility-sized text scales', () => {
    setFontScale(2.0);
    const { result } = renderHook(() => useLargeFontScale());
    expect(result.current).toBe(true);
  });
});
