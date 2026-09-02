// react-native-reanimated 4.6.0 moved the jest matcher ambient types (toHaveAnimatedStyle /
// toHaveAnimatedProps) into a platform-suffixed jestUtils/index.native.d.ts. Metro's Haste
// resolution loads that file at test runtime (see jest/reanimatedResolver.js), but plain
// Node/tsc module resolution never does, so `tsc --noEmit` stopped seeing the augmentation.
// This import exists only to pull that `declare global { namespace jest { ... } }` block into
// the type-checking program; it has no runtime effect.
import 'react-native-reanimated/lib/typescript/jestUtils/index.native';
