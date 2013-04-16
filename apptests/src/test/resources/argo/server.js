var argo = require('argo-server');

argo()
  .use(function(addHandler) {
    addHandler('response', function(env, next) {
      env.response.setHeader('X-Apigee-Ar', 'Go');
      next(env);
    });
  })
  .target('http://weather.yahooapis.com')
  .listen(33334);
