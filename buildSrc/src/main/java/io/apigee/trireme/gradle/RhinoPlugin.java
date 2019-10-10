package io.apigee.trireme.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class RhinoPlugin implements Plugin<Project> {
  @Override
  public void apply(Project p) {
    p.getTasks().create("CompileJavaScript", CompileJavaScript.class);
  }  
}
