function compare(nodeJson, javaCert) {
  function compare(name, v1, v2) {
    if (v1 !== v2) {
      throw new Error('Mismatch in ' + name + ': ' + v1 + ' !== ' + v2);
    }
  }

  function compareCleanWhitespace(name, v1, v2) {
    var w1 = v1.replace('  ', ' ');
    var w2 = v2.replace('  ', ' ');
    if (w1 !== w2) {
      throw new Error('Mismatch in ' + name + ': ' + v1 + ' !== ' + v2);
    }
  }

  // Java and OpenSSL treat non-standard subject entries differently, so just handle
  // the standard ones
  var standardNames = [ 'CN', 'L', 'ST', 'O', 'OU', 'C', 'STREET', 'DC', 'UID' ];

  var nodeCert = JSON.parse(nodeJson);

  if (!javaCert.subject) {
    throw('Java cert is missing subject');
  }

  // Compare standard fields on subject and issuer principal names
  for (var p in nodeCert.subject) {
    if (standardNames.indexOf(p) >= 0) {
      compare('subject.' + p, javaCert.subject[p], nodeCert.subject[p]);
    }
  }
  for (var p in nodeCert.issuer) {
    if (standardNames.indexOf(p) >= 0) {
      compare('issuer.' + p, javaCert.issuer[p], nodeCert.issuer[p]);
    }
  }

  compare('subjectaltname', javaCert.subjectaltname, nodeCert.subjectaltname);

  // Compare dates with normalized whitespace because date formats are a tiny bit different
  compareCleanWhitespace('valid_from', javaCert.valid_from, nodeCert.valid_from);
  compareCleanWhitespace('valid_to', javaCert.valid_to, nodeCert.valid_to);

  if (nodeCert.ext_key_usage) {
    compare('ext_key_usage length', javaCert.ext_key_usage.length, nodeCert.ext_key_usage.length);
    for (var i = 0; i < nodeCert.ext_key_usage.length; i++) {
      compare('ext_key_usage ' + i, javaCert.ext_key_usage[i], nodeCert.ext_key_usage[i]);
    }
  }
}