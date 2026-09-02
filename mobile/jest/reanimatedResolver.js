// react-native-reanimated 4.6.0 split its shared/.native files without shipping the jest
// resolver that makes the split work outside its own monorepo (fix pending upstream:
// https://github.com/software-mansion/react-native-reanimated/pull/10377). Until that ships,
// mirror the resolver the reanimated repo uses internally so Jest resolves these modules to
// their web (JSReanimated-safe) variant instead of the native one.
const workletsResolver = require('react-native-worklets/jest/resolver');

const WEB_ONLY_IN_JEST = new Set([
  'initializers',
  'mutables',
  'mappers',
  'ConfigHelper',
  'UpdateLayoutAnimations',
  'useAnimatedRef',
  'useAnimatedStyle',
  'JSPropsUpdater',
  'updateProps',
  'util',
  'css/component/AnimatedComponent',
]);

/** @type {import('jest-resolve').SyncResolver} */
module.exports = (request, options) => {
  const basename = request.split('/').pop();
  const isWebOnly = [...WEB_ONLY_IN_JEST].some((entry) =>
    entry.includes('/') ? request.endsWith(entry) : basename === entry
  );
  if (
    request.startsWith('.') &&
    isWebOnly &&
    options.basedir.includes('react-native-reanimated')
  ) {
    return options.defaultResolver(request, {
      ...options,
      extensions: options.extensions?.filter((ext) => !ext.includes('native')),
    });
  }

  return workletsResolver(request, options);
};
