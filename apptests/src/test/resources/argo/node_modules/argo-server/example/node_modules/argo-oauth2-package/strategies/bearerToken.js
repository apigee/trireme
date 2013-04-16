var crypto = require('crypto');
var uuid = require('node-uuid').v4;

function BearerTokenStrategy(options) {
  options = options || {};
  this.type = 'bearer';
  this.scheme = 'Bearer';
}

BearerTokenStrategy.prototype.generate = function(salt) {
  var unique = uuid();
  var salt = salt || 'ARGO__#+';
  var expires = Date.now() + 3600;

  var shasum = crypto.createHash('sha1');
  shasum.update(unique + salt + expires.toString());
  var hashed = shasum.digest('binary');

  var bufs = new Buffer(unique.length + hashed.length);
  bufs.write(unique);
  bufs.write(hashed, unique.length, bufs.length - unique.length, 'binary');

  return bufs.toString('base64').replace(/\+/g,'-').replace(/\//g,'_').replace(/\=/g, '');
};

module.exports = function(options) {
  return new BearerTokenStrategy(options);
};
