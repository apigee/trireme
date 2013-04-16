module.exports = function(addHandler) {
  addHandler('response', function(env, next) {
    env.response.setHeader('Access-Control-Allow-Origin', '*');

    next(env);
  });
};
