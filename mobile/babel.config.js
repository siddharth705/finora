module.exports = function (api) {
  api.cache(true);
  return {
    presets: ['babel-preset-expo'],
    plugins: [
      // Must be listed LAST -- Reanimated's worklets plugin has to run after every other
      // transform has finished rewriting the file, or it can miss code it needs to convert.
      // This project never needed a babel.config.js before Reanimated: Expo's Metro/Jest
      // tooling applies babel-preset-expo as an implicit default when no file is present, and
      // this is functionally identical to that default plus the one plugin Reanimated needs.
      'react-native-worklets/plugin',
    ],
  };
};
