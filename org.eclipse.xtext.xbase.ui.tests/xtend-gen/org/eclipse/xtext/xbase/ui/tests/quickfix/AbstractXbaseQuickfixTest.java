package org.eclipse.xtext.xbase.ui.tests.quickfix;

import com.google.inject.Guice;
import com.google.inject.Injector;
import java.io.InputStream;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.xtext.common.types.access.jdt.IJavaProjectProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.resource.XtextResourceSet;
import org.eclipse.xtext.ui.shared.SharedStateModule;
import org.eclipse.xtext.ui.testing.util.JavaProjectSetupUtil;
import org.eclipse.xtext.util.Modules2;
import org.eclipse.xtext.xbase.XbaseRuntimeModule;
import org.eclipse.xtext.xbase.lib.Exceptions;
import org.eclipse.xtext.xbase.ui.internal.XbaseActivator;
import org.eclipse.xtext.xbase.ui.tests.AbstractXbaseUITestCase;
import org.eclipse.xtext.xbase.ui.tests.quickfix.TestQuickfixXbaseUIModule;
import org.junit.Assert;

@SuppressWarnings("all")
public abstract class AbstractXbaseQuickfixTest extends AbstractXbaseUITestCase implements IJavaProjectProvider {
  private IProject demandCreateProject;
  
  private static Injector injector = Guice.createInjector(
    Modules2.mixin(new XbaseRuntimeModule(), new SharedStateModule(), 
      new TestQuickfixXbaseUIModule(XbaseActivator.getInstance())));
  
  @Override
  public void tearDown() throws Exception {
    if ((this.demandCreateProject != null)) {
      JavaProjectSetupUtil.deleteProject(this.demandCreateProject);
    }
    super.tearDown();
  }
  
  @Override
  public IJavaProject getJavaProject(final ResourceSet resourceSet) {
    final String projectName = this.getProjectName();
    IJavaProject javaProject = JavaProjectSetupUtil.findJavaProject(projectName);
    if (((javaProject == null) || (!javaProject.exists()))) {
      try {
        this.demandCreateProject = AbstractXbaseUITestCase.createPluginProject(projectName);
        javaProject = JavaProjectSetupUtil.findJavaProject(projectName);
      } catch (final Throwable _t) {
        if (_t instanceof CoreException) {
          final CoreException e = (CoreException)_t;
          String _message = e.getMessage();
          String _plus = ("cannot create java project due to: " + _message);
          String _plus_1 = (_plus + " / ");
          String _plus_2 = (_plus_1 + e);
          Assert.fail(_plus_2);
        } else {
          throw Exceptions.sneakyThrow(_t);
        }
      }
    }
    return javaProject;
  }
  
  protected String getProjectName() {
    String _simpleName = this.getClass().getSimpleName();
    return (_simpleName + "Project");
  }
  
  @Override
  public XtextResource getResourceFor(final InputStream stream) {
    try {
      Resource _createResource = this.getResourceSet().createResource(URI.createURI(("Test." + this.fileExtension)));
      final XtextResource result = ((XtextResource) _createResource);
      result.load(stream, null);
      return result;
    } catch (final Throwable _t) {
      if (_t instanceof Exception) {
        final Exception e = (Exception)_t;
        throw new RuntimeException(e);
      } else {
        throw Exceptions.sneakyThrow(_t);
      }
    }
  }
  
  public XtextResourceSet getResourceSet() {
    XtextResourceSet _xblockexpression = null;
    {
      final XtextResourceSet set = this.<XtextResourceSet>get(XtextResourceSet.class);
      set.setClasspathURIContext(this.getJavaProject(set));
      _xblockexpression = set;
    }
    return _xblockexpression;
  }
  
  @Override
  public Injector getInjector() {
    return AbstractXbaseQuickfixTest.injector;
  }
}
