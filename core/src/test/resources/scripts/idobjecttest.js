function assert(a) {
  if (!a) {
    throw new Error('Not ' + a);
  }
}

function assertEquals(a, b) {
  if (a !== b) {
    throw new Error(a + ' != ' + b);
  }
}

function testObject(o) {
  assertEquals(o.baz, 0);
  assertEquals(o.no, 999);
  o.baz = 99;
  assertEquals(o.baz, 99);

  o.additional = 123;
  assertEquals(o.additional, 123);

  assertEquals(o.callFoo(), 'Foo!');
  assertEquals(o.callBar('Bar'), 'Hello, Bar!');

  try {
    o.no = 1000;
    assert(false);
  } catch (e) {
  }

  assertEquals(typeof o.callFoo, 'function');
  assertEquals(typeof o.callBar, 'function');
}

testObject(javaId);

var jsId = new IdObject();
testObject(jsId);
