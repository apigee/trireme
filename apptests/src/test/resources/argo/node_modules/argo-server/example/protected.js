var argo = require('../');
var cors = require('./cors');
var oauth2 = require('argo-oauth2-package');
var oauthOptions = require('./oauth_options');

var oauth = oauth2.createProvider(oauthOptions);

argo()
  .use(oauth)
  .use(cors)
  .target('http://weather.yahooapis.com')
  .get('/weather/forecasts', require('./forecasts'))
  .listen(process.env.PORT || 3000);
