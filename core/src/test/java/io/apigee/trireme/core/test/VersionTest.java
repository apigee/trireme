package io.apigee.trireme.core.test;

import io.apigee.trireme.core.internal.Version;
import org.junit.Test;

import static org.junit.Assert.*;

public class VersionTest {
  @Test
  public void testVersion() {
    assertNotNull(Version.TRIREME_VERSION);
    assertNotNull(Version.SSL_VERSION);
  }
}
